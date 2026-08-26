// SPDX-License-Identifier: Apache-2.0
//
// The OpenSearch Contributors require contributions made to
// this file be licensed under the Apache-2.0 license or a
// compatible open source license.

//! The native half of the format seam.
//!
//! Mustang's Java side owns everything up to an Arrow batch: mapping-to-Arrow field writers,
//! schema construction, `VectorSchemaRoot` pooling and rotation. It hands batches across the
//! Arrow C Data Interface. Everything below that boundary is a *format*, and this crate is the
//! one place that says what a format is.
//!
//! The shape is taken from Milvus Loon, whose `Format` abstract factory dispatches five formats
//! (`parquet`, `vortex`, `lance-table`, `iceberg-table`, `paimon-table`) behind a single
//! `Format::get(name)` lookup, with a `PlainFormat` base class absorbing the work common to
//! single-file formats. That is why adding a format in Loon is small, and it is the property this
//! crate exists to reproduce.
//!
//! See `sandbox/LOON-INTEGRATION-DESIGN.md`.

pub mod registry;
pub mod types;

pub use registry::{format_for, register_format, registered_formats};
pub use types::{FileMetadata, FlushedFile, FormatError, Result, SortSpec, WriterConfig};

use arrow::array::RecordBatch;
use arrow_schema::SchemaRef;

/// One open writer producing a single data file.
///
/// Implementations are not required to be thread-safe: the Java `VSRManager` serialises calls per
/// file, exactly as it does today for Parquet.
pub trait FormatWriter: Send {
    /// Consumes one batch. Repeatable until [`FormatWriter::finalize`].
    fn write(&mut self, batch: RecordBatch) -> Result<()>;

    /// Finalises the file and reports its metadata. The writer is spent afterwards.
    fn finalize(self: Box<Self>) -> Result<FlushedFile>;

    /// Bytes this writer currently holds outside the JVM heap. Feeds
    /// `IndexingExecutionEngine::getNativeBytesUsed`.
    fn native_bytes_used(&self) -> u64 {
        0
    }
}

/// Reads a set of files of one format back as Arrow.
///
/// Returning batches (rather than a file path) is what lets a non-Parquet format be queried:
/// the analytics backend registers the stream as a table provider instead of handing DataFusion a
/// path it would have to know how to open. Loon's `rust/milvus-storage-datafusion` crate is prior
/// art for the same handoff.
pub trait FormatReader: Send {
    /// The schema these files carry.
    fn schema(&self, file: &str) -> Result<SchemaRef>;

    /// Exact row count, read from the file's own metadata rather than by scanning.
    fn row_count(&self, file: &str) -> Result<u64>;

    /// Opens a scan. `projection` is a set of top-level column indices, or `None` for all.
    fn scan(
        &self,
        files: &[String],
        projection: Option<&[usize]>,
    ) -> Result<Box<dyn Iterator<Item = Result<RecordBatch>> + Send>>;
}

/// A storage format: the factory the registry hands out.
pub trait Format: Send + Sync {
    /// The name this format registers under. Must equal the Java `DataFormat.name()`.
    fn name(&self) -> &'static str;

    /// The file extension this format writes, without the leading dot.
    fn extension(&self) -> &'static str;

    /// Opens a writer for one output file.
    fn writer(&self, cfg: &WriterConfig) -> Result<Box<dyn FormatWriter>>;

    /// Opens a reader for files of this format.
    fn reader(&self) -> Result<Box<dyn FormatReader>>;

    /// Whether this format can apply an index sort while writing. A format that cannot must be
    /// given an already-sorted stream, or the index must declare no sort.
    fn supports_write_sort(&self) -> bool {
        false
    }
}
