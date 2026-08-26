// SPDX-License-Identifier: Apache-2.0
//
// The OpenSearch Contributors require contributions made to
// this file be licensed under the Apache-2.0 license or a
// compatible open source license.

//! Arrow IPC as a first-class OpenSearch data format.
//!
//! This crate exists to answer one question about `sandbox/LOON-INTEGRATION-DESIGN.md`: once the
//! format-agnostic Arrow document pipeline has been extracted and the seam introduced, **how much
//! code does a second format actually cost?** Everything here is the whole native half of a real,
//! queryable format — no mapping-to-Arrow conversion, no schema construction, no
//! `VectorSchemaRoot` pooling, no row-id assignment, no catalog integration. Those are inherited.
//!
//! Arrow IPC was chosen as the prototype format deliberately:
//!
//! * `arrow-ipc` is **already** a workspace dependency — the Parquet writer uses it for staging
//!   files (`.arrow_ipc_staging`), so the seam is tested without introducing a dependency.
//! * It is a genuine format worth having: no encoding cost on ingest, and DataFusion reads it.
//! * It isolates the *seam* question from the *dependency* question. Adding Vortex or Lance is the
//!   same amount of glue plus a real third-party crate.

use std::fs::File;
use std::io::BufWriter;

use arrow::array::RecordBatch;
use arrow_ipc::reader::FileReader;
use arrow_ipc::writer::FileWriter;
use arrow_schema::SchemaRef;

use opensearch_format_seam::types::{FileMetadata, FlushedFile, FormatError, Result, WriterConfig};
use opensearch_format_seam::{Format, FormatReader, FormatWriter};

/// Format version stamped into `WriterFileSet.formatVersion`.
const FORMAT_VERSION: i32 = 1;

/// The format singleton registered under `arrow-ipc`.
pub struct ArrowIpcFormat;

/// The registered name. Must match the Java `DataFormat.name()`.
pub const FORMAT_NAME: &str = "arrow-ipc";

impl Format for ArrowIpcFormat {
    fn name(&self) -> &'static str {
        FORMAT_NAME
    }

    fn extension(&self) -> &'static str {
        "arrow"
    }

    fn writer(&self, cfg: &WriterConfig) -> Result<Box<dyn FormatWriter>> {
        // An IPC file writer needs the schema up front, but the seam only supplies it with the
        // first batch. Defer creation rather than widening the seam for one format's convenience.
        Ok(Box::new(ArrowIpcWriter {
            path: cfg.path.clone(),
            writer: None,
            rows: 0,
            batches: 0,
        }))
    }

    fn reader(&self) -> Result<Box<dyn FormatReader>> {
        Ok(Box::new(ArrowIpcReader))
    }

    /// IPC preserves write order and applies no sort of its own, so an index-sorted IPC index
    /// requires the sort to happen upstream. Reporting `false` is what lets the engine decide.
    fn supports_write_sort(&self) -> bool {
        false
    }
}

/// Writes one Arrow IPC file.
struct ArrowIpcWriter {
    path: String,
    writer: Option<FileWriter<BufWriter<File>>>,
    rows: u64,
    batches: u32,
}

impl FormatWriter for ArrowIpcWriter {
    fn write(&mut self, batch: RecordBatch) -> Result<()> {
        if self.writer.is_none() {
            let file = File::create(&self.path)
                .map_err(|e| FormatError::Backend(format!("create {}: {e}", self.path)))?;
            let w = FileWriter::try_new(BufWriter::new(file), batch.schema_ref())
                .map_err(|e| FormatError::Backend(format!("open ipc writer: {e}")))?;
            self.writer = Some(w);
        }
        let w = self
            .writer
            .as_mut()
            .ok_or_else(|| FormatError::InvalidState("writer not initialised".into()))?;
        self.rows += batch.num_rows() as u64;
        self.batches += 1;
        w.write(&batch)
            .map_err(|e| FormatError::Backend(format!("write batch: {e}")))
    }

    fn finalize(self: Box<Self>) -> Result<FlushedFile> {
        let path = self.path.clone();
        let (rows, batches) = (self.rows, self.batches);
        match self.writer {
            // A generation that accepted no document produces no file. The engine treats a
            // zero-row flush as "nothing to publish", matching the Parquet path.
            None => Ok(FlushedFile {
                path,
                metadata: FileMetadata {
                    format_version: FORMAT_VERSION,
                    num_rows: 0,
                    created_by: Some(created_by()),
                    crc32: 0,
                    num_blocks: 0,
                    file_size: Some(0),
                    footer_size: None,
                },
                row_id_mapping: None,
            }),
            Some(mut w) => {
                w.finish()
                    .map_err(|e| FormatError::Backend(format!("finish ipc file: {e}")))?;
                // Drop the writer before measuring so the BufWriter is flushed to disk.
                drop(w);
                let file_size = std::fs::metadata(&path)
                    .map_err(|e| FormatError::Backend(format!("stat {path}: {e}")))?
                    .len();
                Ok(FlushedFile {
                    path,
                    metadata: FileMetadata {
                        format_version: FORMAT_VERSION,
                        num_rows: rows,
                        created_by: Some(created_by()),
                        crc32: 0,
                        num_blocks: batches,
                        file_size: Some(file_size),
                        // IPC's trailing footer length is not exposed by arrow-ipc; a reader gets
                        // no single-IO-footer benefit here, so report absence rather than guess.
                        footer_size: None,
                    },
                    // IPC never reorders, so `__row_id__` is unchanged and no remap is needed.
                    row_id_mapping: None,
                })
            }
        }
    }
}

/// Reads Arrow IPC files back as batches.
struct ArrowIpcReader;

impl ArrowIpcReader {
    fn open(path: &str) -> Result<FileReader<File>> {
        let f = File::open(path).map_err(|e| FormatError::Backend(format!("open {path}: {e}")))?;
        FileReader::try_new(f, None)
            .map_err(|e| FormatError::Backend(format!("read ipc footer {path}: {e}")))
    }
}

impl FormatReader for ArrowIpcReader {
    fn schema(&self, file: &str) -> Result<SchemaRef> {
        Ok(Self::open(file)?.schema())
    }

    /// IPC records per-batch row counts in its footer, so this does not scan data.
    fn row_count(&self, file: &str) -> Result<u64> {
        let reader = Self::open(file)?;
        let mut n = 0u64;
        for b in reader {
            n += b
                .map_err(|e| FormatError::Backend(format!("read batch: {e}")))?
                .num_rows() as u64;
        }
        Ok(n)
    }

    fn scan(
        &self,
        files: &[String],
        projection: Option<&[usize]>,
    ) -> Result<Box<dyn Iterator<Item = Result<RecordBatch>> + Send>> {
        let proj = projection.map(|p| p.to_vec());
        let mut readers = Vec::with_capacity(files.len());
        for f in files {
            let file = File::open(f).map_err(|e| FormatError::Backend(format!("open {f}: {e}")))?;
            readers.push(
                FileReader::try_new(file, proj.clone())
                    .map_err(|e| FormatError::Backend(format!("read ipc footer {f}: {e}")))?,
            );
        }
        Ok(Box::new(readers.into_iter().flatten().map(|b| {
            b.map_err(|e| FormatError::Backend(format!("read batch: {e}")))
        })))
    }
}

fn created_by() -> String {
    format!("opensearch-arrow-ipc-format {}", env!("CARGO_PKG_VERSION"))
}

/// Registers this format. Called from the native library's init alongside the other formats.
pub fn register() {
    static FORMAT: ArrowIpcFormat = ArrowIpcFormat;
    opensearch_format_seam::register_format(&FORMAT);
}

#[cfg(test)]
mod tests {
    use super::*;
    use arrow::array::{Int64Array, StringArray};
    use arrow_schema::{DataType, Field, Schema};
    use opensearch_format_seam::types::SortSpec;
    use std::sync::Arc;

    fn batch(ids: Vec<i64>, names: Vec<&str>) -> RecordBatch {
        let schema = Arc::new(Schema::new(vec![
            Field::new("id", DataType::Int64, false),
            Field::new("name", DataType::Utf8, false),
        ]));
        RecordBatch::try_new(
            schema,
            vec![
                Arc::new(Int64Array::from(ids)),
                Arc::new(StringArray::from(names)),
            ],
        )
        .unwrap()
    }

    fn cfg(path: &str) -> WriterConfig {
        WriterConfig {
            path: path.to_string(),
            index_name: "test-index".into(),
            writer_generation: 7,
            sort: SortSpec::default(),
        }
    }

    #[test]
    fn round_trips_batches_and_reports_metadata() {
        let dir = std::env::temp_dir().join("seam-ipc-roundtrip");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("gen7.arrow").to_string_lossy().to_string();

        let fmt = ArrowIpcFormat;
        let mut w = fmt.writer(&cfg(&path)).unwrap();
        w.write(batch(vec![1, 2], vec!["a", "b"])).unwrap();
        w.write(batch(vec![3], vec!["c"])).unwrap();
        let flushed = w.finalize().unwrap();

        assert_eq!(flushed.metadata.num_rows, 3);
        assert_eq!(flushed.metadata.num_blocks, 2);
        assert_eq!(flushed.metadata.format_version, FORMAT_VERSION);
        assert!(flushed.metadata.file_size.unwrap() > 0);
        // IPC preserves write order, so the engine must not be handed a remap.
        assert!(flushed.row_id_mapping.is_none());

        let r = fmt.reader().unwrap();
        assert_eq!(r.row_count(&path).unwrap(), 3);
        assert_eq!(r.schema(&path).unwrap().fields().len(), 2);

        let total: usize = r
            .scan(std::slice::from_ref(&path), None)
            .unwrap()
            .map(|b| b.unwrap().num_rows())
            .sum();
        assert_eq!(total, 3);

        std::fs::remove_file(&path).ok();
    }

    #[test]
    fn projection_narrows_the_scan() {
        let dir = std::env::temp_dir().join("seam-ipc-projection");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("gen1.arrow").to_string_lossy().to_string();

        let fmt = ArrowIpcFormat;
        let mut w = fmt.writer(&cfg(&path)).unwrap();
        w.write(batch(vec![1, 2, 3], vec!["a", "b", "c"])).unwrap();
        w.finalize().unwrap();

        let r = fmt.reader().unwrap();
        let cols = r
            .scan(std::slice::from_ref(&path), Some(&[0]))
            .unwrap()
            .next()
            .unwrap()
            .unwrap()
            .num_columns();
        assert_eq!(cols, 1, "projection should drop the un-requested column");

        std::fs::remove_file(&path).ok();
    }

    #[test]
    fn a_generation_with_no_documents_publishes_nothing() {
        let dir = std::env::temp_dir().join("seam-ipc-empty");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("gen0.arrow").to_string_lossy().to_string();

        let w = ArrowIpcFormat.writer(&cfg(&path)).unwrap();
        let flushed = w.finalize().unwrap();
        assert_eq!(flushed.metadata.num_rows, 0);
        assert!(!std::path::Path::new(&path).exists(), "no file should be created");
    }
}
