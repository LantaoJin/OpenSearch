// SPDX-License-Identifier: Apache-2.0
//
// The OpenSearch Contributors require contributions made to
// this file be licensed under the Apache-2.0 license or a
// compatible open source license.

//! Name-based format dispatch — the direct analogue of Loon's `Format::get(name)`.
//!
//! Formats register once at library init. The FFI entry points then take a format name alongside
//! their existing arguments, so adding a format adds no new native symbols: one `format_write`
//! serves every format, rather than a `parquet_write` / `vortex_write` / ... family.

use std::collections::HashMap;
use std::sync::{OnceLock, RwLock};

use crate::types::{FormatError, Result};
use crate::Format;

fn registry() -> &'static RwLock<HashMap<&'static str, &'static dyn Format>> {
    static REGISTRY: OnceLock<RwLock<HashMap<&'static str, &'static dyn Format>>> = OnceLock::new();
    REGISTRY.get_or_init(|| RwLock::new(HashMap::new()))
}

/// Registers a format. Called once per format during native library initialisation.
///
/// Re-registering the same name replaces the previous entry; that is a programming error in
/// production but convenient in tests, so it is not a panic.
pub fn register_format(format: &'static dyn Format) {
    registry()
        .write()
        .expect("format registry poisoned")
        .insert(format.name(), format);
}

/// Looks up a format by name, mirroring Loon's `Format::get`.
pub fn format_for(name: &str) -> Result<&'static dyn Format> {
    registry()
        .read()
        .expect("format registry poisoned")
        .get(name)
        .copied()
        .ok_or_else(|| FormatError::UnknownFormat(name.to_string()))
}

/// Every registered format name, for diagnostics and for the Java side to validate settings
/// against what the native library actually carries.
pub fn registered_formats() -> Vec<&'static str> {
    let mut names: Vec<&'static str> = registry()
        .read()
        .expect("format registry poisoned")
        .keys()
        .copied()
        .collect();
    names.sort_unstable();
    names
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::WriterConfig;
    use crate::{FormatReader, FormatWriter};

    struct Dummy(&'static str);

    impl Format for Dummy {
        fn name(&self) -> &'static str {
            self.0
        }
        fn extension(&self) -> &'static str {
            "dummy"
        }
        fn writer(&self, _cfg: &WriterConfig) -> Result<Box<dyn FormatWriter>> {
            Err(FormatError::InvalidState("test double".into()))
        }
        fn reader(&self) -> Result<Box<dyn FormatReader>> {
            Err(FormatError::InvalidState("test double".into()))
        }
    }

    #[test]
    fn unknown_format_is_an_error_not_a_panic() {
        assert!(matches!(
            format_for("no-such-format"),
            Err(FormatError::UnknownFormat(_))
        ));
    }

    #[test]
    fn registered_format_is_retrievable_and_listed() {
        static A: Dummy = Dummy("seam-test-a");
        register_format(&A);
        assert_eq!(format_for("seam-test-a").unwrap().name(), "seam-test-a");
        assert!(registered_formats().contains(&"seam-test-a"));
    }
}
