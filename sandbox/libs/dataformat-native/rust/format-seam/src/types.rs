// SPDX-License-Identifier: Apache-2.0
//
// The OpenSearch Contributors require contributions made to
// this file be licensed under the Apache-2.0 license or a
// compatible open source license.

//! Values crossing the seam. Deliberately format-independent: every field here means the same
//! thing for Parquet, Arrow IPC, or anything added later.

use std::fmt;

/// Result alias for seam operations.
pub type Result<T> = std::result::Result<T, FormatError>;

/// A failure attributable to a format implementation.
#[derive(Debug)]
pub enum FormatError {
    /// No format is registered under the requested name.
    UnknownFormat(String),
    /// The caller violated the writer or reader contract.
    InvalidState(String),
    /// The underlying format library or filesystem failed.
    Backend(String),
}

impl fmt::Display for FormatError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            FormatError::UnknownFormat(n) => write!(f, "unknown data format: {n}"),
            FormatError::InvalidState(m) => write!(f, "invalid writer/reader state: {m}"),
            FormatError::Backend(m) => write!(f, "format backend failure: {m}"),
        }
    }
}

impl std::error::Error for FormatError {}

/// The index sort to apply while writing, in the parallel-array form native writers expect.
///
/// Mirrors the Java `FormatSortConfig`, which resolves it from `index.sort.*` settings.
#[derive(Debug, Clone, Default)]
pub struct SortSpec {
    /// Column names, in sort precedence order.
    pub columns: Vec<String>,
    /// Per-column descending flag, parallel to `columns`.
    pub descending: Vec<bool>,
    /// Per-column nulls-first flag, parallel to `columns`.
    pub nulls_first: Vec<bool>,
}

impl SortSpec {
    /// Whether any sort column is configured.
    pub fn is_empty(&self) -> bool {
        self.columns.is_empty()
    }
}

/// Everything a format needs in order to open one output file.
#[derive(Debug, Clone)]
pub struct WriterConfig {
    /// Absolute path of the file to create.
    pub path: String,
    /// Index name, for native-side diagnostics only.
    pub index_name: String,
    /// The Mustang writer generation this file belongs to.
    pub writer_generation: i64,
    /// The index sort, possibly empty.
    pub sort: SortSpec,
}

/// Metadata of one finalised file.
///
/// `file_size` and `footer_size` are carried because they let a reader skip a size probe and read
/// a footer in a single request — the optimisation Loon records as `file_size` / `footer_size`
/// properties on each column-group file. A format that cannot report them uses `None`.
#[derive(Debug, Clone)]
pub struct FileMetadata {
    /// Format-internal version stamp, surfaced as `WriterFileSet.formatVersion`.
    pub format_version: i32,
    /// Rows written, surfaced as `WriterFileSet.numRows`.
    pub num_rows: u64,
    /// Writer identification string, if the format records one.
    pub created_by: Option<String>,
    /// CRC32 of the file, for the pre-computed-checksum upload path. Zero when not computed.
    pub crc32: u32,
    /// Row groups, chunks, or whatever unit the format blocks by.
    pub num_blocks: u32,
    /// Total file size in bytes.
    pub file_size: Option<u64>,
    /// Footer size in bytes, when the format has a trailing footer.
    pub footer_size: Option<u64>,
}

/// A finalised file plus, when the write reordered rows, the resulting row-id remapping.
#[derive(Debug, Clone)]
pub struct FlushedFile {
    /// Path of the file that was written.
    pub path: String,
    /// Its metadata.
    pub metadata: FileMetadata,
    /// `new_row_id -> old_row_id` when an index sort reordered rows; `None` when write order was
    /// preserved. Feeds Mustang's `PackedRowIdMapping`, which keeps `__row_id__` stable across the
    /// reorder — the same invariant Loon maintains when compaction rewrites positions.
    pub row_id_mapping: Option<Vec<i64>>,
}
