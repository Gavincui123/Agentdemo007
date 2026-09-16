---
name: java-home
description: JAVA_HOME path needed to run ./mvnw for this project (env has none set)
metadata: 
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-03T09:55:43.199Z
---

本机未设置 JAVA_HOME，`./mvnw` 直接运行会报 "JAVA_HOME environment variable is not defined correctly"。

Java 17（JBR）位于：
`/Users/cuizhifeng/Library/Java/JavaVirtualMachines/jbr-17.0.14/Contents/Home`

**How to apply:** 所有 `./mvnw` 命令前加 `export JAVA_HOME=/Users/cuizhifeng/Library/Java/JavaVirtualMachines/jbr-17.0.14/Contents/Home &&`。本机还装有 Java 8（Corretto / AppletPlugin），勿误用。
