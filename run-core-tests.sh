#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/core-tests.jar"
kotlinc \
  "$ROOT/core/src/main/kotlin/com/comic/xenvyx/id/reader/ChapterWindowManager.kt" \
  "$ROOT/core/src/main/kotlin/com/comic/xenvyx/id/reader/DownloadQueue.kt" \
  "$ROOT/core/src/test/kotlin/com/comic/xenvyx/id/reader/ChapterWindowManagerTest.kt" \
  "$ROOT/core/src/test/kotlin/com/comic/xenvyx/id/reader/DownloadQueueTest.kt" \
  -include-runtime -d "$OUT"
java -cp "$OUT" com.comic.xenvyx.id.reader.ChapterWindowManagerTestKt
java -cp "$OUT" com.comic.xenvyx.id.reader.DownloadQueueTestKt
