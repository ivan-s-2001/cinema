fontations = get_option('fontations')

if (fontations.enabled())
  rust = import('rust')

  generated_fontconfig = rust.bindgen(
    input : fontconfig_h,
    output : 'fontconfig.rs',
    include_directories : [ '../' ],
    args : [
      '--merge-extern-blocks',
      '--allowlist-item=(FcCharSet.*|FC_(SLANT|WEIGHT|WIDTH)_.*|FcFontSet(Add|Create|Destroy).*|FcLangSet(Create|Destroy|Copy|Add|HasLang)|FcWeightFromOpenType.*)',
      '--raw-line=#![allow(nonstandard_style,unused)]',
      '--raw-line= ',
      '--raw-line=pub mod fcint;',
    ],
    # FC_NO_MT=1 is added here to reduce required headers in bindings generation.
    # It does not mean a functional change in multi-threading behavior.
    c_args : ['-DBINDGEN_IGNORE_VISIBILITY=1', '-DFC_NO_MT=1'],
  )

  generated_fcint = rust.bindgen(
    input : '../src/fcint.h',
    output : 'fcint.rs',
    include_directories : [ '../' ],
    args : [
      '--merge-extern-blocks',
      '--allowlist-item=(FcPattern.*|FcRange.*|FC_.*_OBJECT|FcCharSet.*|FcFreeTypeLangSet)',
      '--blocklist-type=(FcCharSet|FcLangSet)',
      '--raw-line=#![allow(nonstandard_style,unused)]',
      '--raw-line= ',
      '--raw-line=pub use crate::FcCharSet; pub use crate::FcLangSet;',
    ],
    # FC_NO_MT=1 is added here to reduce required headers in bindings generation.
    # It does not mean a functional change in multi-threading behavior.
    c_args : ['-DBINDGEN_IGNORE_VISIBILITY=1', '-DFC_NO_MT=1'],
  )

  bindgen_lib = static_library(
    'fc_fontations_bindgen',
    sources: [generated_fontconfig, generated_fcint],
    rust_abi : 'rust',
  )

  fontations_query_lib = static_library(
    'fc_fontations_query',
    include_directories : [ '../', '../fontconfig' ],
    sources: ['../src/fcfontations.c', fcstdint_h, alias_headers],
  )

  fc_fontations = static_library(
    'fc_fontations',
    sources: ['mod.rs'],
    link_with: [bindgen_lib, pattern_lib, fontations_query_lib],
    rust_abi: 'c',
    dependencies: [
      dependency('skrifa-0.31-rs'),
      dependency('read-fonts-0.29-rs'),
      dependency('font-types-0.9-rs'),
      dependency('libc-0.2-rs'),
    ],
    install: true,

  )

endif
