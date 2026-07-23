/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

//! Rust-native Arrow-Flight shuffle exchange.
//!
//! Replaces the `Rust -> Java(blocking stream_next) -> Rust` hash-shuffle data path with a
//! Rust <-> Rust Arrow-Flight/tonic stream between DataFusion stages, keeping the JVM in charge of
//! planning, routing, and worker placement.
//!
//! ## Model
//!
//! - **Per node**: a tonic Flight server ([`ShuffleFlightService`]) is bound on the already-running
//!   [`RuntimeManager::io_runtime`](crate::runtime_manager::RuntimeManager) — no new runtime. It
//!   receives partition streams via `do_put` and forwards each decoded [`RecordBatch`] into the
//!   consumer-side [`PartitionStreamSender`](crate::partition_stream::PartitionStreamSender) that
//!   backs a registered `StreamingTable` (the SAME seam Java's `senderSend` feeds today).
//! - **Producer**: after the native stage hash-partitions its output, each bucket is streamed by a
//!   [`push_partition`] Flight client to the target node's server under a
//!   [`ShuffleKey`]-derived Flight descriptor. Backpressure is the gRPC send window (later: port
//!   df-distributed's shared byte reservation if the window alone under-throttles).
//!
//! ## Route registry
//!
//! A node's inbound routes are keyed by [`ShuffleKey`] = `(query_id, stage_id, partition, side)` —
//! the exact tuple `ShuffleBufferManager` keys on today and that the coordinator already computes
//! (`UnifiedDispatch.resolveTargetWorkerNodeIds`). The consumer registers a route (parking a
//! `PartitionStreamSender`) before the plan executes; an inbound `do_put` for that key drains into
//! it. Completion is Flight end-of-stream per expected producer (replaces `expectedSenders` /
//! `awaitReady`).
//!
//! This module provides the server + route registry + hash-partition + client-push primitives. The
//! consumer seam (registering a Flight-fed `StreamingTable`) is wired via the FFM
//! `register_flight_partition_stream_*` entry points; the producer seam (draining the native producer
//! stream straight into `push_partitioned`) and cluster-wide port publication are follow-ups.

use std::collections::HashMap;
use std::net::SocketAddr;
use std::pin::Pin;
use std::sync::{Arc, Mutex};

use parking_lot::RwLock as PlRwLock;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

use arrow_flight::decode::FlightRecordBatchStream;
use arrow_flight::encode::FlightDataEncoderBuilder;
use arrow_flight::error::FlightError;
use arrow_flight::flight_service_client::FlightServiceClient;
use arrow_flight::flight_service_server::{FlightService, FlightServiceServer};
use arrow_flight::{
    Action, ActionType, Criteria, Empty, FlightData, FlightDescriptor, FlightInfo, HandshakeRequest,
    HandshakeResponse, PollInfo, PutResult, SchemaResult, Ticket,
};
use datafusion::arrow::datatypes::SchemaRef;
use datafusion::arrow::record_batch::RecordBatch;
use datafusion::common::DataFusionError;
use futures::{stream, Stream, StreamExt, TryStreamExt};
use tokio::runtime::Handle;
use tokio::sync::mpsc;
use tonic::transport::Server;
use tonic::{Request, Response, Status, Streaming};

use crate::partition_stream::{PartitionStreamSender, SendOutcome};

/// Identifies a single shuffle partition stream between a producer stage and a consumer worker.
/// Mirrors `ShuffleBufferManager`'s `queryId:stageId:partition` key plus the join `side`.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct ShuffleKey {
    pub query_id: String,
    pub stage_id: i32,
    pub partition: i32,
    /// "left" / "right" for a join shuffle; "agg" for an aggregate shuffle.
    pub side: String,
}

impl ShuffleKey {
    /// Encodes the key into the single-element Flight descriptor path the producer sends and the
    /// server matches on. Format: `q:<query>|s:<stage>|p:<partition>|side:<side>`.
    pub fn to_descriptor_path(&self) -> String {
        format!(
            "q:{}|s:{}|p:{}|side:{}",
            self.query_id, self.stage_id, self.partition, self.side
        )
    }

    /// Inverse of [`to_descriptor_path`]. Returns `None` on a malformed path.
    pub fn from_descriptor_path(path: &str) -> Option<ShuffleKey> {
        let mut query_id = None;
        let mut stage_id = None;
        let mut partition = None;
        let mut side = None;
        for part in path.split('|') {
            let (k, v) = part.split_once(':')?;
            match k {
                "q" => query_id = Some(v.to_string()),
                "s" => stage_id = v.parse().ok(),
                "p" => partition = v.parse().ok(),
                "side" => side = Some(v.to_string()),
                _ => {}
            }
        }
        Some(ShuffleKey {
            query_id: query_id?,
            stage_id: stage_id?,
            partition: partition?,
            side: side?,
        })
    }
}

/// An inbound shuffle route: the shared sender that feeds a consumer's `StreamingTable`, plus the
/// count of producers still expected to send to it. Hash shuffle is FAN-IN — N producer stages send
/// to one `(stage, partition, side)` — so the channel must reach EOF (all senders dropped) only after
/// the LAST producer's `do_put` completes. `expected_senders` mirrors the Java `expectedSenders` the
/// `ShuffleWorkerSetupHandler` already computes.
struct Route {
    /// `Arc` so each concurrent `do_put` holds a clone; the channel EOFs when the last clone drops.
    sender: Arc<PartitionStreamSender>,
    /// Producers not yet finished. Decremented as each `do_put` for this key completes; the entry is
    /// removed (releasing the registry's own `Arc` clone) when it hits zero.
    remaining_senders: usize,
}

/// Batches a producer decoded for a key whose consumer route hasn't registered YET. The scheduler
/// dispatches producers eagerly but a consumer worker stage only starts (and registers its route)
/// after its producer children SUCCEED — so the producer MUST be able to complete without a route, or
/// the two deadlock (producer waits for route, consumer waits for producer). This is the Flight analog
/// of `ShuffleBufferManager` buffering early producer bytes until the worker drains them: `do_put`
/// decodes into here and returns success immediately; `register` later replays it into the sender.
struct PendingBuffer {
    /// Fully-decoded batches this key received before any route registered, in arrival order. Bounded
    /// by the producers' partition output (same footprint as the Java buffer-all path).
    batches: Vec<RecordBatch>,
    /// Producers that have finished pushing into the pending buffer (each completed `do_put`). Used to
    /// initialize the route's `remaining_senders` correctly when it finally registers.
    producers_done: usize,
}

/// What a `do_put` should do with its decoded batches, decided atomically under the registry lock so a
/// concurrent `register` can never strand them (the producer-first / consumer-first race).
enum RouteDispatch {
    /// A consumer route already exists: stream these batches into its sender, then `producer_done`.
    Route(Arc<PartitionStreamSender>, Vec<RecordBatch>),
    /// No route yet: the batches were buffered for `register` to replay. The producer's `do_put`
    /// completes successfully so its stage can SUCCEED and the consumer stage starts.
    Buffered,
}

/// Both maps live under ONE lock so `register` (drain-pending → insert-route) and `do_put`
/// (check-route → else-buffer) are mutually atomic. Two independent locks would leave a window where
/// `do_put` misses the route, `register` drains an empty pending map and installs the route, and then
/// `do_put` buffers into pending that nobody will ever replay — the consumer hangs.
#[derive(Default)]
struct RegistryInner {
    routes: HashMap<ShuffleKey, Route>,
    /// Batches received for keys whose consumer route hasn't registered yet (see [`PendingBuffer`]).
    pending: HashMap<ShuffleKey, PendingBuffer>,
}

/// Per-node registry of inbound shuffle routes, keyed by [`ShuffleKey`]. A consumer registers a route
/// (its `StreamingTable` sender + expected producer count); the Flight server resolves the route on
/// each matching `do_put` and forwards decoded batches into the shared sender. A `do_put` that arrives
/// BEFORE the consumer registered buffers its batches (the [`PendingBuffer`] early-arrival path) so the
/// producer can complete — `register` then replays the buffer into the sender.
///
/// `Mutex<HashMap>` keyed by a small tuple; contention is per-`do_put`, not per-batch, so it is not a
/// hot path (a `DashMap` is available as a crate dep if profiling ever shows lock contention here).
#[derive(Clone, Default)]
pub struct ShuffleRouteRegistry {
    inner: Arc<Mutex<RegistryInner>>,
}

impl ShuffleRouteRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    /// Register the sender + expected producer count for `key`. Called by the consumer seam (the
    /// Flight-fed replacement for `register_partition_stream_on_session_context`). `expected_senders`
    /// is how many producer `do_put`s will target this key; the channel EOFs after the last one.
    ///
    /// If producers already buffered batches for this key (they ran before the consumer stage —
    /// the normal case, since producers dispatch eagerly and a consumer worker starts only after its
    /// producer children SUCCEED), those buffered batches are REPLAYED into the sender and the
    /// already-finished producers are counted against `remaining_senders`. So a route can register
    /// after ALL its producers already completed — it drains the buffer and then EOFs.
    ///
    /// The replay runs ASYNCHRONOUSLY on the process-global IO runtime (never inline): the consumer
    /// plan is not draining the channel yet at register time, so a blocking replay of more than the
    /// bounded channel's capacity would deadlock the calling (FFM) thread. Only spawns when there is a
    /// non-empty buffer, so the common register-before-any-producer path needs no runtime handle.
    pub fn register(&self, key: ShuffleKey, sender: PartitionStreamSender, expected_senders: usize) {
        let sender = Arc::new(sender);
        let expected = expected_senders.max(1);

        // Atomically claim any early-arrival buffer and install the route (single lock closes the
        // do_put-buffers-after-register-drains race). `remaining` excludes producers already finished
        // into the buffer; if all expected producers already delivered, no route is installed.
        let buffered = {
            let mut inner = self.inner.lock().expect("registry poisoned");
            let (buffered, producers_done) = match inner.pending.remove(&key) {
                Some(pb) => (pb.batches, pb.producers_done),
                None => (Vec::new(), 0),
            };
            let remaining = expected.saturating_sub(producers_done);
            if remaining > 0 {
                inner
                    .routes
                    .insert(key.clone(), Route { sender: Arc::clone(&sender), remaining_senders: remaining });
            }
            buffered
        };

        if buffered.is_empty() {
            // No early arrivals to replay. If no route was installed (all producers already done with
            // zero rows), `sender` drops here → the StreamingTable sees clean EOF.
            return;
        }

        // Replay the buffered batches asynchronously. A live producer's `do_put` (a DIFFERENT producer;
        // the buffered ones already finished) may interleave — cross-producer order is not guaranteed
        // by hash shuffle anyway. The spawned task's `sender` clone keeps the channel open until replay
        // completes, so EOF only happens after both the replay finishes AND the route's own Arc drops.
        match native_bridge_common::io_runtime::io_handle().or_else(|| Handle::try_current().ok()) {
            Some(handle) => {
                let replay_sender = Arc::clone(&sender);
                handle.spawn(async move {
                    for batch in buffered {
                        if matches!(replay_sender.send_async(Ok(batch)).await, SendOutcome::ReceiverDropped) {
                            break; // consumer finished early; nothing more to feed
                        }
                    }
                });
            }
            None => {
                // No IO runtime to drive the async replay (misconfiguration). Fail the sender so the
                // consumer sees a terminal Err rather than hanging on a buffer that will never replay.
                sender.fail(DataFusionError::Execution(
                    "flight-shuffle: no IO runtime handle available to replay buffered shuffle batches".to_string(),
                ));
            }
        }
    }

    /// Try to acquire a sender clone for `key` without waiting. Used by tests and cleanup assertions.
    fn try_get_sender(&self, key: &ShuffleKey) -> Option<Arc<PartitionStreamSender>> {
        self.inner
            .lock()
            .expect("registry poisoned")
            .routes
            .get(key)
            .map(|r| Arc::clone(&r.sender))
    }

    /// Atomically decide what a `do_put` does with its decoded `batches`: stream into an existing route
    /// or buffer for later replay. Done under ONE lock so a racing `register` cannot strand the batches
    /// (see [`RegistryInner`]).
    fn route_or_buffer(&self, key: &ShuffleKey, batches: Vec<RecordBatch>) -> RouteDispatch {
        let mut inner = self.inner.lock().expect("registry poisoned");
        if let Some(route) = inner.routes.get(key) {
            return RouteDispatch::Route(Arc::clone(&route.sender), batches);
        }
        let entry = inner
            .pending
            .entry(key.clone())
            .or_insert_with(|| PendingBuffer { batches: Vec::new(), producers_done: 0 });
        entry.batches.extend(batches);
        entry.producers_done += 1;
        RouteDispatch::Buffered
    }

    /// Mark one producer stream for `key` finished. When the last expected producer completes, remove
    /// the entry — dropping the registry's `Arc<PartitionStreamSender>` clone so that, once the
    /// in-flight `do_put` handlers also drop theirs, the channel closes and the `StreamingTable` sees
    /// clean EOF.
    fn producer_done(&self, key: &ShuffleKey) {
        let mut inner = self.inner.lock().expect("registry poisoned");
        if let Some(route) = inner.routes.get_mut(key) {
            route.remaining_senders = route.remaining_senders.saturating_sub(1);
            if route.remaining_senders == 0 {
                inner.routes.remove(key);
            }
        }
    }

    /// Terminally fail and drop a single route: mark its sender failed — so the
    /// consumer's `StreamingTable` yields an `Err`, never a stuck stream — then remove the entry,
    /// releasing the registry's `Arc` regardless of `remaining_senders`. For a producer that will
    /// never reach `do_put` (crash before opening Flight, wrong-node/port, or cancelled pre-descriptor)
    /// there is otherwise no path that decrements the count, so the route leaks and the consumer hangs.
    /// Returns true if a route was present.
    fn fail_route(&self, key: &ShuffleKey, reason: &str) -> bool {
        let removed = self.inner.lock().expect("registry poisoned").routes.remove(key);
        if let Some(route) = removed {
            route.sender.fail(DataFusionError::Execution(format!("flight-shuffle route failed: {reason}")));
            true
        } else {
            false
        }
    }

    /// Sweep every route AND pending buffer belonging to `query_id`: the query-cancel / terminal
    /// cleanup hook, the Flight analog of `ShuffleBufferManager.clearForQuery`. Any consumer still
    /// parked on a route for this query gets a terminal `Err` and the registry Arcs are released; any
    /// buffered-but-never-consumed batches are dropped so a cancelled query leaks nothing on the node.
    /// Returns the number of routes + pending buffers cleared.
    pub fn clear_query(&self, query_id: &str) -> usize {
        let mut inner = self.inner.lock().expect("registry poisoned");
        let route_victims: Vec<ShuffleKey> =
            inner.routes.keys().filter(|k| k.query_id == query_id).cloned().collect();
        for k in &route_victims {
            if let Some(route) = inner.routes.remove(k) {
                route
                    .sender
                    .fail(DataFusionError::Execution(format!("flight-shuffle query {query_id} cancelled/terminated")));
            }
        }
        let pending_victims: Vec<ShuffleKey> =
            inner.pending.keys().filter(|k| k.query_id == query_id).cloned().collect();
        for k in &pending_victims {
            inner.pending.remove(k);
        }
        route_victims.len() + pending_victims.len()
    }
}

/// Node-global convenience: fail+drop all Flight routes for `query_id` on the running node. No-op if
/// the Flight server isn't running. Wire this into the query terminal/cancel path so a cancelled
/// or crashed query can't leak routes or hang a peer consumer.
pub fn clear_query(query_id: &str) -> usize {
    match node() {
        Some(n) => n.registry.clear_query(query_id),
        None => 0,
    }
}

type BoxTonicStream<T> = Pin<Box<dyn Stream<Item = Result<T, Status>> + Send + 'static>>;

/// The per-node native Flight server. `do_put` decodes an inbound partition stream and forwards it
/// into the registered consumer sender for its [`ShuffleKey`].
#[derive(Clone)]
pub struct ShuffleFlightService {
    registry: ShuffleRouteRegistry,
}

impl ShuffleFlightService {
    pub fn new(registry: ShuffleRouteRegistry) -> Self {
        Self { registry }
    }
}

#[tonic::async_trait]
impl FlightService for ShuffleFlightService {
    type HandshakeStream = BoxTonicStream<HandshakeResponse>;
    type ListFlightsStream = BoxTonicStream<FlightInfo>;
    type DoGetStream = BoxTonicStream<FlightData>;
    type DoPutStream = BoxTonicStream<PutResult>;
    type DoExchangeStream = BoxTonicStream<FlightData>;
    type DoActionStream = BoxTonicStream<arrow_flight::Result>;
    type ListActionsStream = BoxTonicStream<ActionType>;

    async fn do_put(
        &self,
        request: Request<Streaming<FlightData>>,
    ) -> Result<Response<Self::DoPutStream>, Status> {
        let mut inbound = request.into_inner();

        // The first FlightData message carries the descriptor (FlightDataEncoderBuilder emits a
        // schema message tagged with the descriptor). Peek it to resolve the route key.
        let first = inbound
            .try_next()
            .await?
            .ok_or_else(|| Status::invalid_argument("empty do_put stream"))?;
        let descriptor = first
            .flight_descriptor
            .as_ref()
            .ok_or_else(|| Status::invalid_argument("first FlightData has no descriptor"))?;
        let path = descriptor
            .path
            .first()
            .ok_or_else(|| Status::invalid_argument("descriptor has empty path"))?;
        let key = ShuffleKey::from_descriptor_path(path)
            .ok_or_else(|| Status::invalid_argument(format!("unparseable shuffle key: {path}")))?;

        let head = stream::once(async move { Ok::<FlightData, Status>(first) });
        let reassembled = head
            .chain(inbound)
            .map_err(|status: Status| FlightError::ExternalError(Box::new(status)));
        let mut decoder = FlightRecordBatchStream::new_from_flight_data(reassembled);

        // Resolve the route ONCE. If it exists, stream each decoded batch straight into the consumer's
        // channel as it arrives — gRPC flow-control + the bounded channel backpressure the producer's
        // encoder, so the whole partition is never materialized on this node (the streaming memory
        // profile tier-2 targets; buffering-all here is the q17 OOM axis). If the route does NOT exist
        // yet, the producer arrived before its consumer registered (the normal scheduler order:
        // producers dispatch eagerly, a consumer worker starts only after its producer children
        // SUCCEED) — decode into a buffer so this producer can COMPLETE, breaking the
        // producer-waits-for-route / consumer-waits-for-producer deadlock; register() replays it. Only
        // the early-arrival case buffers, mirroring the Java `ShuffleBufferManager`.
        if let Some(sender) = self.registry.try_get_sender(&key) {
            loop {
                match decoder.try_next().await {
                    Ok(Some(batch)) => {
                        if matches!(sender.send_async(Ok(batch)).await, SendOutcome::ReceiverDropped) {
                            break; // consumer finished early (e.g. LimitExec) — stop, clean.
                        }
                    }
                    Ok(None) => break,
                    Err(e) => {
                        // A truncated/failed producer stream: fail the route so the consumer sees an Err
                        // (never a silent EOF that would look like a complete-but-short partition).
                        let msg = format!("flight decode failed: {e}");
                        self.registry.fail_route(&key, &msg);
                        return Err(Status::internal(msg));
                    }
                }
            }
            drop(sender);
            // Decrement fan-in EXACTLY once per do_put so the channel EOFs once the last producer
            // completes and all handlers drop their clones.
            self.registry.producer_done(&key);
        } else {
            let mut decoded: Vec<RecordBatch> = Vec::new();
            loop {
                match decoder.try_next().await {
                    Ok(Some(batch)) => decoded.push(batch),
                    Ok(None) => break,
                    Err(e) => {
                        let msg = format!("flight decode failed: {e}");
                        self.registry.fail_route(&key, &msg);
                        return Err(Status::internal(msg));
                    }
                }
            }
            // Atomically (single lock) hand the buffer to a route that may have registered while we
            // decoded, else park it for replay — this closes the race where `register` slips between a
            // route check and a buffer insert and strands the batches.
            match self.registry.route_or_buffer(&key, decoded) {
                RouteDispatch::Route(sender, batches) => {
                    for batch in batches {
                        if matches!(sender.send_async(Ok(batch)).await, SendOutcome::ReceiverDropped) {
                            break;
                        }
                    }
                    drop(sender);
                    self.registry.producer_done(&key);
                }
                RouteDispatch::Buffered => {
                    // Batches buffered; register() will replay them and count this producer as done.
                }
            }
        }
        Ok(Response::new(Box::pin(stream::empty())))
    }

    async fn handshake(
        &self,
        _: Request<Streaming<HandshakeRequest>>,
    ) -> Result<Response<Self::HandshakeStream>, Status> {
        Err(Status::unimplemented("handshake"))
    }
    async fn list_flights(
        &self,
        _: Request<Criteria>,
    ) -> Result<Response<Self::ListFlightsStream>, Status> {
        Err(Status::unimplemented("list_flights"))
    }
    async fn get_flight_info(
        &self,
        _: Request<FlightDescriptor>,
    ) -> Result<Response<FlightInfo>, Status> {
        Err(Status::unimplemented("get_flight_info"))
    }
    async fn poll_flight_info(
        &self,
        _: Request<FlightDescriptor>,
    ) -> Result<Response<PollInfo>, Status> {
        Err(Status::unimplemented("poll_flight_info"))
    }
    async fn get_schema(
        &self,
        _: Request<FlightDescriptor>,
    ) -> Result<Response<SchemaResult>, Status> {
        Err(Status::unimplemented("get_schema"))
    }
    async fn do_get(&self, _: Request<Ticket>) -> Result<Response<Self::DoGetStream>, Status> {
        Err(Status::unimplemented("do_get"))
    }
    async fn do_exchange(
        &self,
        _: Request<Streaming<FlightData>>,
    ) -> Result<Response<Self::DoExchangeStream>, Status> {
        Err(Status::unimplemented("do_exchange"))
    }
    async fn do_action(&self, _: Request<Action>) -> Result<Response<Self::DoActionStream>, Status> {
        Err(Status::unimplemented("do_action"))
    }
    async fn list_actions(
        &self,
        _: Request<Empty>,
    ) -> Result<Response<Self::ListActionsStream>, Status> {
        Err(Status::unimplemented("list_actions"))
    }
}

// HTTP/2 flow-control tuning. The default 64 KiB stream window throttles a bulk Arrow-batch stream to
// a stop-and-wait crawl on a fat link (measured ~4x slowdown in the microbench). Adaptive windows + a
// large initial window remove that; production Flight transports set the same. Server + client must agree.
const H2_CONN_WINDOW: u32 = 32 * 1024 * 1024;
const H2_STREAM_WINDOW: u32 = 16 * 1024 * 1024;

/// A running per-node shuffle Flight server: its bound address, the shared route registry, and a
/// cancellation handle to stop it. Held in the process-global [`FLIGHT_SHUFFLE_NODE`] so the consumer
/// seam (route registration) and the producer (push target) reach the same registry/port.
pub struct FlightShuffleNode {
    pub bound_addr: SocketAddr,
    pub registry: ShuffleRouteRegistry,
    cancel: CancellationToken,
    task: JoinHandle<()>,
    /// Set true by [`stop`] BEFORE cancelling, so the serve task knows this exit is a deliberate
    /// shutdown and must NOT clear the global singleton (the caller already `take()`s it). A serve exit
    /// with this still false = the server died on its own → the task clears the singleton.
    graceful: Arc<std::sync::atomic::AtomicBool>,
}

impl FlightShuffleNode {
    /// The port the OS actually bound (equals the requested port unless 0 = ephemeral was requested).
    pub fn port(&self) -> u16 {
        self.bound_addr.port()
    }

    /// Signal the server to stop and abort its task. Idempotent.
    pub fn stop(&self) {
        self.graceful.store(true, std::sync::atomic::Ordering::SeqCst);
        self.cancel.cancel();
        self.task.abort();
    }
}

/// Clear the global singleton IFF the currently-installed node is the one bound at `addr` (identity
/// by bound address). Guards against a stale serve task clearing a NEWER node that a later
/// `start_on_runtime` swapped in.
fn clear_node_if(addr: SocketAddr) {
    let mut guard = FLIGHT_SHUFFLE_NODE.write();
    if guard.as_ref().map(|n| n.bound_addr) == Some(addr) {
        *guard = None;
    }
}

/// Process-global handle to this node's shuffle Flight server. `None` until [`start_on_runtime`] runs.
static FLIGHT_SHUFFLE_NODE: PlRwLock<Option<Arc<FlightShuffleNode>>> = PlRwLock::new(None);

/// Accessor for the running node (registry + port). `None` before start / after stop.
pub fn node() -> Option<Arc<FlightShuffleNode>> {
    FLIGHT_SHUFFLE_NODE.read().clone()
}

/// Binds the native shuffle Flight server on `127.0.0.1:port` (binds loopback today; binding the
/// node's external transport host is a follow-up), spawns it on `runtime`, and installs it as the process-global
/// node. `port = 0` asks the OS for an ephemeral port. Returns the ACTUAL bound port so Java can
/// publish it. Replaces any previously-running node (stops the old one first).
///
/// Errors as `String` (surfaced through the FFM `?` wrapper): bind failure, or a runtime that can't
/// accept the spawn. The bind is done synchronously (via a std listener converted to tokio) so the
/// returned port is guaranteed live before this returns.
pub fn start_on_runtime(runtime: &Handle, port: u16) -> Result<u16, String> {
    // Bind synchronously with a std listener so the port is known before we return; hand it to tonic.
    let std_listener = std::net::TcpListener::bind(("127.0.0.1", port))
        .map_err(|e| format!("flight-shuffle bind 127.0.0.1:{port}: {e}"))?;
    std_listener
        .set_nonblocking(true)
        .map_err(|e| format!("flight-shuffle set_nonblocking: {e}"))?;
    let bound_addr = std_listener
        .local_addr()
        .map_err(|e| format!("flight-shuffle local_addr: {e}"))?;

    let registry = ShuffleRouteRegistry::new();
    let cancel = CancellationToken::new();

    let svc = ShuffleFlightService::new(registry.clone());
    let cancel_child = cancel.clone();
    // `graceful` distinguishes a cancel-driven shutdown (stop_node) from an unexpected serve exit, so
    // the task only clears the global singleton in the latter case (a serve error after publish
    // otherwise leaves the singleton `Some`, and Java keeps choosing Flight against a dead server →
    // hang). A clean stop already `take()`s the singleton, so it must NOT clear again.
    let graceful = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let graceful_child = Arc::clone(&graceful);
    // Convert the std listener to a tokio one INSIDE the spawned task — `from_std` needs a runtime
    // context, and `runtime.block_on` here would panic if start_on_runtime is itself called from a
    // runtime thread ("Cannot start a runtime from within a runtime"). The std listener is `Send`.
    let task = runtime.spawn(async move {
        let listener = match tokio::net::TcpListener::from_std(std_listener) {
            Ok(l) => l,
            Err(e) => {
                log::error!("flight-shuffle tokio listener on {bound_addr}: {e}");
                clear_node_if(bound_addr);
                return;
            }
        };
        let incoming = tokio_stream::wrappers::TcpListenerStream::new(listener);
        // Raise the server decode/encode caps off tonic's 4 MiB default: one shuffle FlightData frame
        // carries a whole RecordBatch, which can exceed 4 MiB for wide/large partitions (observed 5.7 MiB
        // on TPC-H q21) → "decoded message length too large". The client already sets usize::MAX; the
        // server must match on the inbound `do_put` decode path. Flow-control still bounds in-flight bytes
        // (adaptive window + H2_STREAM_WINDOW); this cap only rejects an oversized single frame.
        let serve = Server::builder()
            .http2_adaptive_window(Some(true))
            .initial_connection_window_size(H2_CONN_WINDOW)
            .initial_stream_window_size(H2_STREAM_WINDOW)
            .tcp_nodelay(true)
            .add_service(
                FlightServiceServer::new(svc)
                    .max_decoding_message_size(usize::MAX)
                    .max_encoding_message_size(usize::MAX),
            )
            .serve_with_incoming_shutdown(incoming, async move { cancel_child.cancelled().await });
        let result = serve.await;
        // If we exited WITHOUT a graceful cancel, the server died on its own — clear the singleton so
        // isFlightShuffleServerRunning() reports false and callers fall back to the Java path.
        if !graceful_child.load(std::sync::atomic::Ordering::SeqCst) {
            if let Err(e) = &result {
                log::warn!("flight-shuffle server on {bound_addr} exited with error: {e}");
            } else {
                log::warn!("flight-shuffle server on {bound_addr} exited unexpectedly (no cancel)");
            }
            clear_node_if(bound_addr);
        }
    });

    let node = Arc::new(FlightShuffleNode {
        bound_addr,
        registry,
        cancel,
        task,
        graceful,
    });
    // Swap in the new node, stopping any prior one. Take the write lock, replace, THEN stop the old
    // one after inserting the new — so the old serve task's `clear_node_if(old_addr)` is a no-op
    // (the installed node is already the new one), never clobbering the new registration.
    let prev = {
        let mut guard = FLIGHT_SHUFFLE_NODE.write();
        guard.replace(Arc::clone(&node))
    };
    if let Some(prev) = prev {
        prev.stop();
    }
    log::info!("flight-shuffle server bound at {}", node.bound_addr);
    Ok(node.port())
}

/// Stops and clears the process-global node, if any. Idempotent.
pub fn stop_node() {
    if let Some(node) = FLIGHT_SHUFFLE_NODE.write().take() {
        node.stop();
        log::info!("flight-shuffle server stopped ({})", node.bound_addr);
    }
}

/// Consumer seam: register a route on the running node's registry so inbound Flight `do_put`s for
/// `key` feed the given `sender` (which backs a `StreamingTable` registered under the same
/// `input-<producerStageId>` id). `expected_senders` is the fan-in count. Errors if the node isn't
/// running (the Flight transport was not enabled) — the caller must fall back to the Java path.
pub fn register_route(
    key: ShuffleKey,
    sender: PartitionStreamSender,
    expected_senders: usize,
) -> Result<(), String> {
    let node = node().ok_or_else(|| "flight-shuffle server not running".to_string())?;
    node.registry.register(key, sender, expected_senders);
    Ok(())
}

/// Bounded in-flight `FlightData` messages between the encoder and the `do_put` request stream. Small
/// by design: the producer's encoder stalls when the socket + this buffer are full, so a large
/// partition is NOT fully materialized as encoded `FlightData` before the network drains it — gRPC
/// flow-control propagates back through this channel to the encoder. Matches the consumer-side
/// bounded-mpsc contract.
const ENCODE_INFLIGHT: usize = 4;

/// Producer side: stream `batches` for `key` to the target node's Flight server at `uri`
/// (`http://host:port`). Encodes with `FlightDataEncoderBuilder` and streams the encoded messages
/// straight into `do_put` through a small bounded channel — the whole partition is never collected
/// into memory first (gRPC flow-control backpressures the encoder). `handle` spawns the encode pump on
/// the io_runtime. An encoder error aborts the pump and surfaces as [`FlightShuffleError::Encode`].
pub async fn push_partition(
    uri: String,
    key: &ShuffleKey,
    schema: SchemaRef,
    batches: Vec<RecordBatch>,
    handle: &Handle,
) -> Result<(), FlightShuffleError> {
    // Delegate to the streaming path over an in-memory batch stream — same bounded-channel encode +
    // do_put, so a pre-materialized Vec and a live stream share one code path.
    push_partition_stream(uri, key.clone(), schema, stream::iter(batches), handle).await
}

/// Hash-partitions `batch` into `partition_count` sub-batches by the columns at `hash_key_indices`,
/// using DataFusion's `BatchPartitioner` (`Partitioning::Hash`, input_partition 0 of 1) — the SAME
/// row→partition mapping `RepartitionExec` / `HashJoinExec` use, so a downstream native join over the
/// shuffled partitions is correct. Returns one `RecordBatch` per partition (empty batch, with the
/// input schema, for partitions that got zero rows). This is the pure-Rust core; the producer stage
/// runs natively, so no FFI is involved (unlike the Java `partition_batch_by_hash`).
pub fn hash_partition_batch(
    batch: RecordBatch,
    hash_key_indices: &[usize],
    partition_count: usize,
) -> Result<Vec<RecordBatch>, DataFusionError> {
    use datafusion::physical_plan::expressions::Column;
    use datafusion::physical_plan::repartition::BatchPartitioner;
    use datafusion::physical_plan::Partitioning;

    let schema = batch.schema();
    let mut exprs: Vec<Arc<dyn datafusion::physical_expr::PhysicalExpr>> =
        Vec::with_capacity(hash_key_indices.len());
    for &idx in hash_key_indices {
        let field = schema.field(idx);
        exprs.push(Arc::new(Column::new(field.name(), idx)));
    }
    let partitioning = Partitioning::Hash(exprs, partition_count);
    // input_partition 0 of 1 — each batch is partitioned independently (matches api.rs's FFI path).
    let mut partitioner = BatchPartitioner::try_new(
        partitioning,
        datafusion::physical_plan::metrics::Time::default(),
        /* input_partition */ 0,
        /* num_input_partitions */ 1,
    )?;
    let mut per_partition: Vec<Vec<RecordBatch>> =
        (0..partition_count).map(|_| Vec::new()).collect();
    partitioner.partition(batch, |idx, sub| {
        per_partition[idx].push(sub);
        Ok(())
    })?;
    let mut out = Vec::with_capacity(partition_count);
    for part in per_partition {
        if part.is_empty() {
            out.push(RecordBatch::new_empty(Arc::clone(&schema)));
        } else {
            out.push(arrow::compute::concat_batches(&schema, &part)?);
        }
    }
    Ok(out)
}

/// A downstream shuffle target: which node's Flight server to push a partition to, and the
/// `ShuffleKey` that node registered its consumer route under. The coordinator already computes the
/// partition→node mapping (`UnifiedDispatch.resolveTargetWorkerNodeIds`); this is its native mirror.
#[derive(Clone, Debug)]
pub struct PartitionTarget {
    /// `http://host:port` of the target node's native shuffle Flight server.
    pub uri: String,
    /// The route key the target consumer registered (`side` + this producer's stage/query).
    pub key: ShuffleKey,
}

/// Native producer seam: hash-partition each batch in `batches` and Flight-push partition `p` to
/// `targets[p]`. `targets.len()` is the partition count. All partition pushes run concurrently (each
/// is an independent `do_put` to its target node). Returns once every partition's stream is sent.
///
/// This is the native replacement for `DatafusionPartitionedSink.feed`'s
/// FFI-export → IPC-serialize → `AnalyticsShuffleDataAction` tail: no JVM hop, no on-heap byte[]
/// buffer, streamed straight to the consumers' `StreamingTable`s.
pub async fn push_partitioned(
    batches: Vec<RecordBatch>,
    hash_key_indices: Vec<usize>,
    schema: SchemaRef,
    targets: Vec<PartitionTarget>,
    handle: &Handle,
) -> Result<(), FlightShuffleError> {
    let partition_count = targets.len();
    if partition_count == 0 {
        return Err(FlightShuffleError::Encode("push_partitioned: no targets".to_string()));
    }
    // Partition every input batch, accumulating per-partition batches.
    let mut per_partition: Vec<Vec<RecordBatch>> =
        (0..partition_count).map(|_| Vec::new()).collect();
    for batch in batches {
        let parts = hash_partition_batch(batch, &hash_key_indices, partition_count)
            .map_err(|e| FlightShuffleError::Encode(e.to_string()))?;
        for (p, sub) in parts.into_iter().enumerate() {
            if sub.num_rows() > 0 {
                per_partition[p].push(sub);
            }
        }
    }
    // Push each partition to its target concurrently.
    let mut pushes = Vec::with_capacity(partition_count);
    for (p, target) in targets.into_iter().enumerate() {
        let batches_p = std::mem::take(&mut per_partition[p]);
        let schema_p = Arc::clone(&schema);
        let handle_c = handle.clone();
        pushes.push(async move {
            push_partition(target.uri, &target.key, schema_p, batches_p, &handle_c).await
        });
    }
    futures::future::try_join_all(pushes).await?;
    Ok(())
}

/// Fully-streaming producer seam: drain `input` (the producer stage's native output stream),
/// hash-partition each batch by `hash_key_indices`, and Flight-push partition `p` to `targets[p]` —
/// all without materializing the whole stage output. One long-lived `do_put` per target runs
/// concurrently, each fed by a bounded (`ENCODE_INFLIGHT`) channel of `RecordBatch`es; the drain loop
/// hash-partitions each incoming batch and forwards each non-empty sub-batch to its target's channel,
/// so backpressure on any target stalls the drain (bounded memory). This is the native replacement
/// for the `stream_next`-pull + `DatafusionPartitionedSink.feed` producer path — no JVM hop.
///
/// Returns once the input is fully drained and every target's `do_put` completes. Any partition,
/// encode, transport, or connect error aborts and is returned.
pub async fn drain_stream_to_flight<S>(
    mut input: S,
    hash_key_indices: Vec<usize>,
    schema: SchemaRef,
    targets: Vec<PartitionTarget>,
    handle: &Handle,
) -> Result<(), FlightShuffleError>
where
    S: Stream<Item = Result<RecordBatch, DataFusionError>> + Unpin,
{
    let partition_count = targets.len();
    if partition_count == 0 {
        return Err(FlightShuffleError::Encode("drain_stream_to_flight: no targets".to_string()));
    }

    // One RecordBatch channel + one do_put task per target. Each do_put streams its channel's batches
    // over Flight (reusing the streamed push_partition path). Bounded so a slow target backpressures
    // the drain loop rather than buffering the whole partition.
    let mut senders: Vec<mpsc::Sender<RecordBatch>> = Vec::with_capacity(partition_count);
    let mut pushes = Vec::with_capacity(partition_count);
    for target in targets.into_iter() {
        let (tx, rx) = mpsc::channel::<RecordBatch>(ENCODE_INFLIGHT);
        senders.push(tx);
        let schema_p = Arc::clone(&schema);
        let handle_c = handle.clone();
        pushes.push(handle.spawn(async move {
            let batch_stream = tokio_stream::wrappers::ReceiverStream::new(rx);
            push_partition_stream(target.uri, target.key, schema_p, batch_stream, &handle_c).await
        }));
    }

    // Drain loop: partition each input batch, fan sub-batches to per-target channels.
    let mut drain_err: Option<FlightShuffleError> = None;
    while let Some(item) = input.next().await {
        let batch = match item {
            Ok(b) => b,
            Err(e) => {
                drain_err = Some(FlightShuffleError::Encode(format!("producer stream error: {e}")));
                break;
            }
        };
        let parts = match hash_partition_batch(batch, &hash_key_indices, partition_count) {
            Ok(p) => p,
            Err(e) => {
                drain_err = Some(FlightShuffleError::Encode(e.to_string()));
                break;
            }
        };
        for (p, sub) in parts.into_iter().enumerate() {
            if sub.num_rows() > 0 && senders[p].send(sub).await.is_err() {
                // Target's do_put ended early (transport closed); its join below carries the error.
                break;
            }
        }
    }
    // Close all channels so each do_put sees end-of-input and completes.
    drop(senders);
    let results = futures::future::join_all(pushes).await;
    if let Some(e) = drain_err {
        return Err(e);
    }
    for r in results {
        match r {
            Ok(Ok(())) => {}
            Ok(Err(e)) => return Err(e),
            Err(join_err) => return Err(FlightShuffleError::Transport(format!("push task panicked: {join_err}"))),
        }
    }
    Ok(())
}

/// Like [`push_partition`] but fed by a live `RecordBatch` stream instead of a `Vec` — the streaming
/// building block for [`drain_stream_to_flight`]. Connects, then streams the encoder into `do_put`
/// through a bounded channel (backpressured, never fully materialized).
async fn push_partition_stream(
    uri: String,
    key: ShuffleKey,
    schema: SchemaRef,
    batches: impl Stream<Item = RecordBatch> + Send + 'static,
    handle: &Handle,
) -> Result<(), FlightShuffleError> {
    let channel = tonic::transport::Channel::from_shared(uri)
        .map_err(|e| FlightShuffleError::Connect(e.to_string()))?
        .http2_adaptive_window(true)
        .initial_connection_window_size(H2_CONN_WINDOW)
        .initial_stream_window_size(H2_STREAM_WINDOW)
        .tcp_nodelay(true)
        .connect()
        .await
        .map_err(|e| FlightShuffleError::Connect(e.to_string()))?;
    let mut client = FlightServiceClient::new(channel)
        .max_encoding_message_size(usize::MAX)
        .max_decoding_message_size(usize::MAX);

    let descriptor = FlightDescriptor::new_path(vec![key.to_descriptor_path()]);
    let (tx, rx) = mpsc::channel::<FlightData>(ENCODE_INFLIGHT);
    let encode_err: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));
    let encode_err_child = Arc::clone(&encode_err);
    let pump = handle.spawn(async move {
        let mut encoder = FlightDataEncoderBuilder::new()
            .with_schema(schema)
            .with_flight_descriptor(Some(descriptor))
            .build(batches.map(Ok::<_, FlightError>));
        while let Some(item) = encoder.next().await {
            match item {
                Ok(fd) => {
                    if tx.send(fd).await.is_err() {
                        break;
                    }
                }
                Err(e) => {
                    *encode_err_child.lock().unwrap() = Some(e.to_string());
                    break;
                }
            }
        }
    });

    let put_result = client.do_put(tokio_stream::wrappers::ReceiverStream::new(rx)).await;
    let _ = pump.await;
    if let Some(msg) = encode_err.lock().unwrap().take() {
        return Err(FlightShuffleError::Encode(msg));
    }
    put_result.map_err(|e| FlightShuffleError::Transport(e.to_string()))?;
    Ok(())
}

/// Errors surfaced by the producer-side Flight push.
#[derive(Debug)]
pub enum FlightShuffleError {
    Connect(String),
    Encode(String),
    Transport(String),
}

impl std::fmt::Display for FlightShuffleError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            FlightShuffleError::Connect(m) => write!(f, "flight connect: {m}"),
            FlightShuffleError::Encode(m) => write!(f, "flight encode: {m}"),
            FlightShuffleError::Transport(m) => write!(f, "flight transport: {m}"),
        }
    }
}

impl std::error::Error for FlightShuffleError {}

impl From<FlightShuffleError> for DataFusionError {
    fn from(e: FlightShuffleError) -> Self {
        DataFusionError::Execution(e.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::partition_stream::{channel, SingleReceiverPartition};
    use arrow_array::Int64Array;
    use datafusion::arrow::datatypes::{DataType, Field, Schema};
    use datafusion::catalog::streaming::StreamingTable;
    use datafusion::physical_plan::streaming::PartitionStream;
    use datafusion::prelude::SessionContext;
    use std::sync::Arc;

    /// The Flight server lives in a PROCESS-GLOBAL singleton (`FLIGHT_SHUFFLE_NODE`), matching
    /// production (one server per node). Tests that start/stop it must run SERIALLY, else one test's
    /// `start_on_runtime` (which replaces + stops the prior node) tears down a concurrent test's
    /// server → "transport error". This guard serializes them; it is a test-harness concern only.
    static SERIAL: std::sync::Mutex<()> = std::sync::Mutex::new(());

    fn schema() -> SchemaRef {
        Arc::new(Schema::new(vec![Field::new("x", DataType::Int64, false)]))
    }

    fn batch(schema: &SchemaRef, values: &[i64]) -> RecordBatch {
        RecordBatch::try_new(
            Arc::clone(schema),
            vec![Arc::new(Int64Array::from(values.to_vec()))],
        )
        .unwrap()
    }

    #[test]
    fn shuffle_key_descriptor_roundtrip() {
        let key = ShuffleKey {
            query_id: "q-abc".into(),
            stage_id: 7,
            partition: 3,
            side: "left".into(),
        };
        let path = key.to_descriptor_path();
        assert_eq!(ShuffleKey::from_descriptor_path(&path), Some(key));
        assert_eq!(ShuffleKey::from_descriptor_path("garbage"), None);
    }

    /// Fan-in bookkeeping: the route entry is removed — releasing the registry's own sender Arc so the
    /// channel can EOF — ONLY after `producer_done` is called once per expected producer. The do_put
    /// handler must call it on EVERY exit path (clean, dropped, or decode-error); this test asserts the
    /// count logic those calls drive.
    #[test]
    fn producer_done_removes_route_only_after_last_producer() {
        let reg = ShuffleRouteRegistry::new();
        let key = ShuffleKey { query_id: "q".into(), stage_id: 1, partition: 0, side: "left".into() };
        let (sender, _rx) = channel(schema());
        reg.register(key.clone(), sender, 3); // 3 expected producers
        assert!(reg.try_get_sender(&key).is_some(), "route present with 3 pending");
        reg.producer_done(&key); // producer 1 (e.g. clean EOF)
        reg.producer_done(&key); // producer 2 (e.g. consumer-dropped early)
        assert!(reg.try_get_sender(&key).is_some(), "still present — 1 producer outstanding");
        reg.producer_done(&key); // producer 3 (e.g. errored — MUST still decrement, the #2 fix)
        assert!(reg.try_get_sender(&key).is_none(), "route removed after the last producer finishes");
        // Idempotent past zero — a stray extra call must not panic.
        reg.producer_done(&key);
    }

    /// Cancellation cleanup: clear_query fails+drops every route for a query, and the consumer's
    /// receiver yields a terminal Err (not a hang) — the Flight analog of clearForQuery.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn clear_query_fails_parked_routes() {
        use futures::StreamExt;
        let reg = ShuffleRouteRegistry::new();
        let k1 = ShuffleKey { query_id: "qX".into(), stage_id: 1, partition: 0, side: "left".into() };
        let k2 = ShuffleKey { query_id: "qX".into(), stage_id: 1, partition: 1, side: "left".into() };
        let kOther = ShuffleKey { query_id: "qY".into(), stage_id: 1, partition: 0, side: "left".into() };
        let (s1, mut r1) = channel(schema());
        let (s2, _r2) = channel(schema());
        let (sO, _rO) = channel(schema());
        reg.register(k1.clone(), s1, 1);
        reg.register(k2.clone(), s2, 1);
        reg.register(kOther.clone(), sO, 1);

        let cleared = reg.clear_query("qX");
        assert_eq!(cleared, 2, "both qX routes cleared");
        assert!(reg.try_get_sender(&k1).is_none());
        assert!(reg.try_get_sender(&k2).is_none());
        assert!(reg.try_get_sender(&kOther).is_some(), "unrelated query untouched");

        // The parked consumer for k1 must see a terminal Err, not a clean EOF / hang.
        let item = r1.next().await.expect("a terminal item");
        assert!(item.is_err(), "cancelled route surfaces as Err on the StreamingTable");
    }

    /// End-to-end: producer pushes batches over a localhost Flight connection into a registered
    /// route; the consumer's StreamingTable (fed by that route's sender) answers a SQL query
    /// correctly. This is the P0 gate exercised INSIDE the real crate's dep graph.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn flight_push_lands_in_streaming_table() {
        let schema = schema();
        let key = ShuffleKey {
            query_id: "q1".into(),
            stage_id: 2,
            partition: 0,
            side: "left".into(),
        };

        // Consumer seam: channel + StreamingTable, register the sender under the key.
        let (sender, receiver) = channel(Arc::clone(&schema));
        let partition: Arc<dyn PartitionStream> =
            Arc::new(SingleReceiverPartition::new(receiver));
        let table = StreamingTable::try_new(Arc::clone(&schema), vec![partition]).unwrap();
        let ctx = SessionContext::new();
        ctx.register_table("input_2", Arc::new(table)).unwrap();

        let registry = ShuffleRouteRegistry::new();
        registry.register(key.clone(), sender, 1);

        // Bind the native Flight server on an OS-assigned port.
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let incoming = tokio_stream::wrappers::TcpListenerStream::new(listener);
        let svc = ShuffleFlightService::new(registry);
        let server = tokio::spawn(async move {
            Server::builder()
                .add_service(FlightServiceServer::new(svc))
                .serve_with_incoming(incoming)
                .await
        });

        // Producer pushes two batches.
        let batches = vec![batch(&schema, &[1, 2, 3]), batch(&schema, &[10, 20])];
        let handle = Handle::current();
        let uri = format!("http://{addr}");
        let key_c = key.clone();
        let schema_c = Arc::clone(&schema);
        let producer = tokio::spawn(async move {
            push_partition(uri, &key_c, schema_c, batches, &Handle::current()).await
        });

        // Consumer query.
        let df = ctx
            .sql("SELECT sum(x) AS s, count(*) AS c FROM input_2")
            .await
            .unwrap();
        let results = df.collect().await.unwrap();
        producer.await.unwrap().expect("producer push ok");
        server.abort();
        let _ = handle;

        let rb = &results[0];
        let s = rb.column(0).as_any().downcast_ref::<Int64Array>().unwrap().value(0);
        let c = rb.column(1).as_any().downcast_ref::<Int64Array>().unwrap().value(0);
        assert_eq!(s, 36, "sum of Flight-streamed data");
        assert_eq!(c, 5, "row count of Flight-streamed data");
    }

    /// Server lifecycle: start the process-global node on an ephemeral port, register a route on ITS
    /// registry, push to its published port, land in a StreamingTable, then stop it. Exercises
    /// start_on_runtime / node() / stop_node exactly as the FFM entry points do.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn node_lifecycle_start_push_stop() {
        let _serial = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
        let schema = schema();
        let key = ShuffleKey {
            query_id: "q-life".into(),
            stage_id: 5,
            partition: 1,
            side: "right".into(),
        };

        // Start the global node on an ephemeral port (0), on the current runtime.
        let handle = Handle::current();
        let port = start_on_runtime(&handle, 0).expect("server starts");
        assert!(port > 0, "bound a real port");
        let running = node().expect("node installed");
        assert_eq!(running.port(), port);

        // Consumer registers its route on the node's registry (the consumer seam does this via FFM).
        let (sender, receiver) = channel(Arc::clone(&schema));
        let partition: Arc<dyn PartitionStream> = Arc::new(SingleReceiverPartition::new(receiver));
        let table = StreamingTable::try_new(Arc::clone(&schema), vec![partition]).unwrap();
        let ctx = SessionContext::new();
        ctx.register_table("input_5", Arc::new(table)).unwrap();
        running.registry.register(key.clone(), sender, 1);

        // Producer pushes to the node's published port.
        let uri = format!("http://127.0.0.1:{port}");
        let batches = vec![batch(&schema, &[4, 5]), batch(&schema, &[6])];
        push_partition(uri, &key, Arc::clone(&schema), batches, &handle)
            .await
            .expect("push ok");

        let results = ctx
            .sql("SELECT sum(x) AS s, count(*) AS c FROM input_5")
            .await
            .unwrap()
            .collect()
            .await
            .unwrap();
        let rb = &results[0];
        let s = rb.column(0).as_any().downcast_ref::<Int64Array>().unwrap().value(0);
        let c = rb.column(1).as_any().downcast_ref::<Int64Array>().unwrap().value(0);
        assert_eq!(s, 15);
        assert_eq!(c, 3);

        stop_node();
        assert!(node().is_none(), "node cleared after stop");
    }

    /// Fan-in: TWO producers push to ONE route (expected_senders=2). The StreamingTable must see all
    /// rows from both and reach EOF only after BOTH producers finish — the hash-shuffle N-producer
    /// contract that `expected_senders` + `producer_done` implement.
    #[tokio::test(flavor = "multi_thread", worker_threads = 3)]
    async fn fan_in_two_producers_one_route() {
        let _serial = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
        let schema = schema();
        let key = ShuffleKey {
            query_id: "q-fanin".into(),
            stage_id: 9,
            partition: 0,
            side: "left".into(),
        };

        let handle = Handle::current();
        let port = start_on_runtime(&handle, 0).expect("server starts");
        let running = node().expect("node installed");

        let (sender, receiver) = channel(Arc::clone(&schema));
        let partition: Arc<dyn PartitionStream> = Arc::new(SingleReceiverPartition::new(receiver));
        let table = StreamingTable::try_new(Arc::clone(&schema), vec![partition]).unwrap();
        let ctx = SessionContext::new();
        ctx.register_table("input_9", Arc::new(table)).unwrap();
        // TWO expected producers.
        running.registry.register(key.clone(), sender, 2);

        let uri = format!("http://127.0.0.1:{port}");
        // Producer A: rows [1,2,3]; Producer B: rows [100]. Total sum 106, count 4.
        let uri_a = uri.clone();
        let key_a = key.clone();
        let schema_a = Arc::clone(&schema);
        let pa = tokio::spawn(async move {
            let b = batch(&schema_a, &[1, 2, 3]);
            push_partition(uri_a, &key_a, schema_a, vec![b], &Handle::current()).await
        });
        let uri_b = uri.clone();
        let key_b = key.clone();
        let schema_b = Arc::clone(&schema);
        let pb = tokio::spawn(async move {
            let b = batch(&schema_b, &[100]);
            push_partition(uri_b, &key_b, schema_b, vec![b], &Handle::current()).await
        });

        let results = ctx
            .sql("SELECT sum(x) AS s, count(*) AS c FROM input_9")
            .await
            .unwrap()
            .collect()
            .await
            .unwrap();
        pa.await.unwrap().expect("producer A ok");
        pb.await.unwrap().expect("producer B ok");

        let rb = &results[0];
        let s = rb.column(0).as_any().downcast_ref::<Int64Array>().unwrap().value(0);
        let c = rb.column(1).as_any().downcast_ref::<Int64Array>().unwrap().value(0);
        assert_eq!(s, 106, "both producers' rows landed");
        assert_eq!(c, 4);

        stop_node();
    }

    /// Early-arrival buffering (the producer-first / consumer-first deadlock fix): the producer's
    /// `do_put` lands and COMPLETES before the consumer registers its route. The batches must be
    /// buffered so the producer succeeds (no route to wait on), and when the consumer registers later
    /// the buffer is replayed into its StreamingTable so no rows are lost. This is the scheduler-order
    /// case that hangs without the pending buffer (the general scheduler starts a consumer worker only
    /// after its producer children SUCCEED).
    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn producer_before_consumer_buffers_then_replays() {
        let _serial = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
        let schema = schema();
        let handle = Handle::current();
        let port = start_on_runtime(&handle, 0).expect("server starts");
        let running = node().expect("node installed");
        let uri = format!("http://127.0.0.1:{port}");
        let key = ShuffleKey { query_id: "q-early".into(), stage_id: 6, partition: 0, side: "left".into() };

        // Producer pushes BEFORE any route is registered. With the pending buffer this returns Ok
        // (the batches are parked); without it, it would hang waiting for a route that never comes.
        let batches = vec![batch(&schema, &[11, 22, 33]), batch(&schema, &[44])];
        push_partition(uri, &key, Arc::clone(&schema), batches, &handle)
            .await
            .expect("producer completes even though no consumer route exists yet");

        // Now the consumer registers its route — register() must replay the buffered batches.
        let (sender, receiver) = channel(Arc::clone(&schema));
        let part: Arc<dyn PartitionStream> = Arc::new(SingleReceiverPartition::new(receiver));
        let table = StreamingTable::try_new(Arc::clone(&schema), vec![part]).unwrap();
        let ctx = SessionContext::new();
        ctx.register_table("input_6", Arc::new(table)).unwrap();
        running.registry.register(key, sender, 1);

        let results = ctx
            .sql("SELECT sum(x) AS s, count(*) AS c FROM input_6")
            .await
            .unwrap()
            .collect()
            .await
            .unwrap();
        let rb = &results[0];
        let s = rb.column(0).as_any().downcast_ref::<Int64Array>().unwrap().value(0);
        let c = rb.column(1).as_any().downcast_ref::<Int64Array>().unwrap().value(0);
        assert_eq!(s, 11 + 22 + 33 + 44, "buffered rows replayed into the late-registered consumer");
        assert_eq!(c, 4);

        stop_node();
    }

    #[test]
    fn hash_partition_preserves_all_rows_and_is_deterministic() {
        let schema = schema();
        let b = batch(&schema, &[1, 2, 3, 4, 5, 6, 7, 8]);
        let parts = hash_partition_batch(b, &[0], 3).expect("partition ok");
        assert_eq!(parts.len(), 3);
        let total: usize = parts.iter().map(|p| p.num_rows()).sum();
        assert_eq!(total, 8, "no rows lost across partitions");
        // Same key always lands in the same partition (deterministic hash) — re-partition a single-row
        // batch for key 5 and confirm it targets the same partition index both times.
        let which = |v: i64| {
            let p = hash_partition_batch(batch(&schema, &[v]), &[0], 3).unwrap();
            p.iter().position(|b| b.num_rows() == 1).unwrap()
        };
        assert_eq!(which(5), which(5));
    }

    /// FULL native shuffle round-trip: a producer hash-partitions rows across 2 partitions
    /// and Flight-pushes each partition to its own consumer route; the consumer reconstructs the whole
    /// input from both partition StreamingTables (UNION) and a SQL query over it returns every row.
    /// This is the end-to-end "produce → partition → Flight → consume" path, all in-process Rust.
    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn end_to_end_partitioned_shuffle() {
        let _serial = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
        let schema = schema();
        let handle = Handle::current();
        let port = start_on_runtime(&handle, 0).expect("server starts");
        let running = node().expect("node installed");
        let uri = format!("http://127.0.0.1:{port}");

        // Consumer: register ONE route + StreamingTable PER partition (2 partitions). The producer
        // sends partition p under key{partition=p}; each lands in its own table input_p.
        let ctx = SessionContext::new();
        const NPART: usize = 2;
        for p in 0..NPART {
            let key = ShuffleKey {
                query_id: "q-e2e".into(),
                stage_id: 1,
                partition: p as i32,
                side: "left".into(),
            };
            let (sender, receiver) = channel(Arc::clone(&schema));
            let part: Arc<dyn PartitionStream> = Arc::new(SingleReceiverPartition::new(receiver));
            let table = StreamingTable::try_new(Arc::clone(&schema), vec![part]).unwrap();
            ctx.register_table(format!("input_{p}"), Arc::new(table)).unwrap();
            running.registry.register(key, sender, 1); // one producer per partition
        }

        // Producer: 8 rows across 2 input batches, hash-partitioned by column 0 into 2 partitions,
        // each pushed to its matching route.
        let targets: Vec<PartitionTarget> = (0..NPART)
            .map(|p| PartitionTarget {
                uri: uri.clone(),
                key: ShuffleKey { query_id: "q-e2e".into(), stage_id: 1, partition: p as i32, side: "left".into() },
            })
            .collect();
        let batches = vec![batch(&schema, &[1, 2, 3, 4]), batch(&schema, &[5, 6, 7, 8])];
        push_partitioned(batches, vec![0], Arc::clone(&schema), targets, &handle)
            .await
            .expect("push_partitioned ok");

        // Consumer reconstructs the full input (all partitions) and counts — every row must survive
        // the partition→Flight→StreamingTable round trip exactly once.
        let results = ctx
            .sql("SELECT count(*) AS c, sum(x) AS s FROM (SELECT * FROM input_0 UNION ALL SELECT * FROM input_1)")
            .await
            .unwrap()
            .collect()
            .await
            .unwrap();
        let rb = &results[0];
        let c = rb.column(0).as_any().downcast_ref::<Int64Array>().unwrap().value(0);
        let s = rb.column(1).as_any().downcast_ref::<Int64Array>().unwrap().value(0);
        assert_eq!(c, 8, "all 8 rows survived the partitioned Flight shuffle exactly once");
        assert_eq!(s, 1 + 2 + 3 + 4 + 5 + 6 + 7 + 8);

        stop_node();
    }

    /// Fully-streaming producer: drain a live RecordBatch stream through drain_stream_to_flight
    /// (hash-partition each batch → Flight-push per partition) into per-partition consumer
    /// StreamingTables, and verify every row survives exactly once. Exercises the native producer
    /// seam that replaces the stream_next-pull + DatafusionPartitionedSink path.
    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn drain_stream_to_flight_round_trip() {
        use datafusion::physical_plan::stream::RecordBatchStreamAdapter;
        let _serial = SERIAL.lock().unwrap_or_else(|e| e.into_inner());
        let schema = schema();
        let handle = Handle::current();
        let port = start_on_runtime(&handle, 0).expect("server starts");
        let running = node().expect("node installed");
        let uri = format!("http://127.0.0.1:{port}");

        const NPART: usize = 2;
        let ctx = SessionContext::new();
        for p in 0..NPART {
            let key = ShuffleKey { query_id: "q-drain".into(), stage_id: 3, partition: p as i32, side: "left".into() };
            let (sender, receiver) = channel(Arc::clone(&schema));
            let part: Arc<dyn PartitionStream> = Arc::new(SingleReceiverPartition::new(receiver));
            let table = StreamingTable::try_new(Arc::clone(&schema), vec![part]).unwrap();
            ctx.register_table(format!("din_{p}"), Arc::new(table)).unwrap();
            running.registry.register(key, sender, 1);
        }

        // A live producer stream of 3 batches (10 rows total), drained natively into Flight.
        let producer_batches = vec![
            batch(&schema, &[1, 2, 3, 4]),
            batch(&schema, &[5, 6, 7]),
            batch(&schema, &[8, 9, 10]),
        ];
        let input: datafusion::execution::SendableRecordBatchStream = Box::pin(RecordBatchStreamAdapter::new(
            Arc::clone(&schema),
            stream::iter(producer_batches.into_iter().map(Ok)),
        ));
        let targets: Vec<PartitionTarget> = (0..NPART)
            .map(|p| PartitionTarget {
                uri: uri.clone(),
                key: ShuffleKey { query_id: "q-drain".into(), stage_id: 3, partition: p as i32, side: "left".into() },
            })
            .collect();
        drain_stream_to_flight(input, vec![0], Arc::clone(&schema), targets, &handle)
            .await
            .expect("drain_stream_to_flight ok");

        let results = ctx
            .sql("SELECT count(*) AS c, sum(x) AS s FROM (SELECT * FROM din_0 UNION ALL SELECT * FROM din_1)")
            .await
            .unwrap()
            .collect()
            .await
            .unwrap();
        let rb = &results[0];
        let c = rb.column(0).as_any().downcast_ref::<Int64Array>().unwrap().value(0);
        let s = rb.column(1).as_any().downcast_ref::<Int64Array>().unwrap().value(0);
        assert_eq!(c, 10, "all 10 streamed rows survived exactly once");
        assert_eq!(s, (1..=10).sum::<i64>());

        stop_node();
    }
}
