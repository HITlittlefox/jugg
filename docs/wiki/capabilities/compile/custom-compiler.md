---
title: Custom compilers
description: Explains the Jugg custom compiler SPI, stage insertion points, and extension capabilities available to users.
status: active
tags:
  - capability
  - compile
  - custom
---

# Custom compilers

Jugg supports extending its built-in incremental compilation flow through a custom compiler SPI. Custom compilers can insert logic before or after asset, resource, source, minify, DEX, and other stages to handle project-specific generation, transformation, or validation. This page describes integration capabilities and common insertion points. For loading and stage boundaries, see [Custom compiler internals](../../concepts/incremental-compile/custom-compiler.md).

## Supported integration methods

| Scenario | Current support | User-visible result |
|---|---|---|
| Local custom compiler JAR | Supported | Local extension logic enters the Jugg compilation flow |
| Remote custom compiler JAR | Supported | The remote extension participates in compilation after download and validation |
| Pre-stage processing | Supported | Can consume or rewrite input before a built-in stage |
| Post-stage processing | Supported | Can process artifacts after a built-in stage |
| Generate Java before source compilation | Supported with a project-provided adapter | Java/Kotlin annotation inputs can run through the project's processor, and generated Java enters the current compilation |
| Extension execution fails | Supported | If extension compilation logic throws an exception, the current task fails with a visible message |

## Integration

A custom compiler is provided as a JAR. The extension implements `ICompilerCreator`, which creates an `ICompiler` for the current compilation context. Jugg loads implementations from the JAR through SPI.

The project backend only needs to declare the JAR filename, path, and MD5 in the `customCompilers` configuration. The path can be an absolute local path, a path relative to the project directory, or an HTTP/HTTPS address.

ARouter `@Route`, `@Autowired`, and `@Interceptor` have built-in incremental handling, so no adapter needs to be provided. The project must retain its own annotation processor dependencies and the `AROUTER_MODULE_NAME` or `moduleName` argument. Jugg compares annotation values, field types, and route identities that affect generation in the current sources. Only after confirming a change does it collect complete module inputs and run the project's processor; generated Java continues through the current compilation. Equivalent constants, explicit defaults, equivalent imports or aliases, ordinary method bodies, comments, and formatting do not trigger regeneration. Indexes that have completed compilation and deployment are not repeatedly added to ordinary rounds.

When only a route constant changes, existing constant-reference analysis first schedules its users for recompilation. Even if a user's source text is unchanged, Jugg reads the current constant value and regenerates only when it changes the annotation result. ARouter and Kuikly share source reads and syntax analysis within the same round.

When no incremental generation record exists, Jugg recovers old information from matching original Gradle class files and generated sources, including multiple classes in one file and nested classes. It does not need an initial APT run to establish a record. Recovery currently supports standard routes, Provider and Interceptor registrations, and recognizable field injection templates. Missing original classes, disagreement between generated artifacts and classes, a class or DEX deployment overlay in the module or its dependencies, or with unknown module identity, without a matching record, unknown fork templates, unrecognized and unprovable old generic or reference injection types, or unrecoverable documentation-generation inputs cause ARouter generation to be skipped for the round with a visible warn. Current semantic resolution does not cover the entire Java or Kotlin language; unresolved expressions or types follow the same boundary.

Subsequent comparisons use the latest valid Jugg-generated sources and their minimal comparison record. When APT succeeds but language compilation fails, new generated sources are not combined with old classes. If the generation record or sources do not match, the round logs a warn and skips generation instead of falling back to older Gradle annotation values. A changed Gradle generation baseline causes the comparison baseline to be recovered again.

If detection or complete aggregate input analysis fails, old indexes may remain stale; other annotation processors and normal source compilation continue. The next round reevaluates after information is restored. Actual APT execution failures and generated-source compilation errors still stop the current task. Sources generated successfully before a later compilation or deployment failure are completed through existing pending-artifact tracking, without rerunning APT on retry.

Jugg selects AndroidX or legacy Support Fragment from the APK baseline and transforms the processor JAR when needed. It uses AndroidX when both types are present, the query returns no result, or multiple APKs make ownership ambiguous. The current compilation fails explicitly if its classpath lacks the selected Fragment. If removing a route or another change removes a generated class, or generated sources fail to compile, the current task stops; a complete Gradle build is needed to refresh the baseline. Deploying route registration indexes follows the existing app restart policy.

When an ARouter custom compiler configuration remains, Jugg runs only that adapter; source-preprocessing adapters must be recompiled against the current Jugg interface before use. To migrate to built-in handling, remove that configuration entry while retaining other extensions. The current implementation recognizes the three official annotations above. Early `@Param` and forks that change annotations or generation rules require checking actual results; the ARouter name alone does not establish compatibility. See [Annotation processors](./annotation-processors.md) for the supported scope.

> [!NOTE]
> Custom compilers run inside the Jugg compilation flow. They are suitable for extending the incremental flow, not for replacing the complete Gradle task graph.

## Trigger and result

```text
Custom compiler configuration is available
  -> Load and validate the extension JAR
  -> Insert it into the configured compilation stage
  -> Participate in the current incremental compilation
  -> Pass successful artifacts to later stages
```

Choose an insertion point based on the user goal, not on when the JAR is loaded.

## Common insertion points

| Goal | Recommended range |
|---|---|
| Process the current input and artifacts first or last | `atFirst` / `atLast` |
| Process assets and native libraries before or after their stage | `beforeAsset` / `afterAsset` |
| Process resources before or after their stage | `beforeRes` / `afterRes` |
| Process Java/Kotlin before or after their stage | `beforeSource` / `afterSource` |
| Process minification before or after its stage | `beforeMinify` / `afterMinify` |
| Process DEX before or after its stage | `beforeDex` / `afterDex` |

## Related pages

- [Custom compiler guide](../../guide/custom-compiler.md)
- [Jugg backend project configuration](../../guide/jugg-backend/project-config.md)
- [Source compilation](./source-compile.md)
- [Custom compiler internals](../../concepts/incremental-compile/custom-compiler.md)
