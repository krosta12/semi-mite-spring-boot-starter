# semi-mite-spring-boot-starter

[![Java Version](https://img.shields.io/badge/Java-22--26-orange.svg)](https://jdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.x%20/%203.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Technology](https://img.shields.io/badge/Engine-Project%20Panama-blue.svg)](https://openjdk.org/projects/panama/)
[![License](https://img.shields.io/badge/License-Apache%202.0-yellow.svg)](LICENSE)

**semi-mite-spring-boot-starter** is a high-level declarative FFI (Foreign Function Interface) framework for Spring Boot applications built on top of modern **Project Panama** (`java.lang.foreign`).

The framework provides a direct bridge between JVM applications and native C++ code without JNI, C wrappers, generated bindings, or manual serialization layers. It allows developers to invoke native functions using ordinary Java interfaces while leveraging zero-copy memory exchange, deep object marshalling, cyclic graph support, and runtime hot-reloading of native sources.

---

# ⚡ Core Features

> **⚠️ Active Development Notice**
>
> semi-mite-spring-boot-starter is currently under active development.
>
> The core architecture is functional and continuously evolving, but APIs, annotations, internal marshalling mechanisms, and runtime behavior may change between releases as new features and optimizations are introduced.
>
> This project is developed by a single maintainer and is an early-stage engineering effort. While it is fully functional and actively tested, some design decisions may evolve as the system matures.
>
> ⚙️ **Testing scope:** At the current stage, testing has been primarily performed on Windows environments. Cross-platform support (Linux/macOS) is planned and may require additional validation and adjustments.
>
> Feedback, bug reports, feature requests, and contributions are welcome.

## 🚀 Zero-Copy Memory Bridge (`MiteArray`)

Transfer large datasets between Java and C++ using direct off-heap memory access.

Native code operates on memory regions allocated outside of the JVM heap, eliminating traditional heap-to-native copying overhead and significantly reducing GC pressure during computation-heavy workloads.

### Benefits

* Direct off-heap memory access
* Reduced allocation overhead
* No intermediate serialization layers
* Optimized for large numerical workloads
* Designed for high-frequency native execution

---

## 🛠 Declarative Native Clients (`@MiteClient`)

Expose native C++ functions as regular Spring-managed Java interfaces.

The framework automatically:

* Compiles native code
* Loads generated shared libraries
* Resolves exported symbols
* Creates dynamic proxy implementations
* Handles Panama linker configuration

Example:

```java
@MiteClient(script = "cppScripts/math.cpp")
public interface MathClient {

    float calculate(float[] values, int size);

}
```

No JNI code.

No generated bindings.

No manual symbol registration.

---

## 🌳 Recursive Deep Marshalling

Automatically converts complex Java object graphs into continuous native memory structures.

Supported scenarios include:

* Nested DTOs
* Trees
* Linked structures
* Arrays of custom objects
* Collections containing custom objects
* Multi-level object hierarchies

Example:

```java
class Node {

    private String name;

    private List<Node> children;

}
```

The framework recursively traverses the graph and constructs corresponding native structures.

---

## 🔄 Cyclic Graph Support

Traditional recursive traversal fails when object graphs contain cycles.

Example:

```java
class User {

    User manager;

}

class Manager {

    User employee;

}
```

Most serializers eventually trigger:

```text
StackOverflowError
```

semi-mite tracks object identity during marshalling and automatically resolves cyclic references while preserving native address uniqueness.

---

## 🔥 Runtime Hot Reloading

Native source files can be modified while the Spring Boot application remains running.

A background `WatchService` monitors configured C++ sources.

When changes are detected:

1. Source files are re-scanned.
2. Native libraries are rebuilt.
3. Panama bindings are refreshed.
4. Existing Spring beans continue working.

No application restart required.

---

## 🔍 Headerless Function Parsing

No need to manually create Panama `FunctionDescriptor` definitions.

The framework parses C++ source files directly and automatically discovers native functions marked with:

```cpp
// @mite
```

Example:

```cpp
// @mite
float calculate_metrics(const float* coordinates, int totalElements) {
    ...
}
```

Detected signatures are converted into the required Panama descriptors automatically.

---

# 📊 Performance Statistics & Benchmarks

The following benchmarks were executed on a dataset containing **20,000,000 complex objects**.

## Execution Speed

| Implementation           | Execution Time |
| ------------------------ | -------------- |
| Optimized Java Loop      | 63,170 ms      |
| Native C++ via semi-mite | 30,686 ms      |

### Result

🔥 **2.06× faster execution**

---

## Memory Transfer Latency

| Method                       | Latency  |
| ---------------------------- | -------- |
| Standard Heap-to-Native Copy | 39.10 ms |
| MiteArray Zero-Copy Transfer | 4.68 ms  |

### Result

🚀 **11.3× lower transfer overhead**

---

## Architectural Impact

By leveraging direct off-heap memory access and Project Panama's Foreign Function & Memory API, semi-mite minimizes JVM memory movement during native execution and enables near-native throughput for compute-intensive workloads.

---

# 🛠 Requirements

## Java

Supported versions:

* Java 22
* Java 23
* Java 24
* Java 25
* Java 26

---

## Native Compiler

One of the following toolchains must be available:

* GCC (`g++`)
* Clang (`clang++`)

The compiler must be accessible through the system `PATH`.

---

## JVM Startup Flags

Project Panama requires native access permissions.

Launch your application with:

```bash
--enable-native-access=ALL-UNNAMED --enable-preview
```

---

# 📖 Usage Guide

## 1. Enable Mite Infrastructure

```java
package com.example.semi_mite;

import org.example.client.EnableMiteClients;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableMiteClients
public class MiteApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiteApplication.class, args);
    }

}
```

---

## 2. Create a Native Client

```java
package com.example.semi_mite.client;

import com.mite.annotation.MiteClient;
import org.example.memory.MiteArray;
import com.example.semi_mite.dto.CustomDataStructure;

@MiteClient(script = "cppScripts/analytics_processor.cpp")
public interface AnalyticsClient {

    float calculate_metrics(float[] coordinates, int totalElements);

    void process_structures(CustomDataStructure structure, int depth);

    void process_massive_matrix(MiteArray offHeapBuffer, int length);

}
```

---

## 3. Implement Native Functions

```cpp
#include <cmath>

struct CustomDataStructure {

    int id;

    float value;

};

extern "C" {

    // @mite
    float calculate_metrics(const float* coordinates, int totalElements) {

        float sum = 0.0f;

        for (int i = 0; i < totalElements; i++) {
            sum += std::sin(coordinates[i]);
        }

        return sum;

    }

    // @mite
    void process_structures(CustomDataStructure* structure, int depth) {

        if (structure == nullptr) {
            return;
        }

        structure->value *= 2.5f;

    }

}
```

---

# 📝 Logging & Diagnostics

Enable detailed diagnostics using standard Spring Boot logging configuration.

```properties
logging.level.org.example.scanner=DEBUG

logging.level.org.example.parser=TRACE
```

### Scanner Logs

Provides:

* Compilation commands
* File watcher activity
* Native rebuild events
* Dynamic library refresh events

### Parser Logs

Provides:

* Signature detection
* Type resolution
* Marshalling diagnostics
* Function registration details

---

# 🏗 Internal Architecture

```text
[ Spring Application Context ]
                │
                ▼
[ @MiteClient Proxy Interface ]
                │
                ▼
[ Deep Marshalling Layer ]
                │
                ▼
[ Panama Downcall Linker ]
                │
                ▼
[ Native Shared Library ]
```

---

## A. Signature Parsing Engine

The parser scans configured directories for C++ source files.

Functions marked with:

```cpp
// @mite
```

are automatically registered.

The engine:

* Normalizes pointer declarations
* Resolves primitive types
* Detects custom structures
* Builds Panama FunctionDescriptors
* Registers callable native methods

---

## B. Intelligent Debounced File Watching

Modern IDEs frequently save files in multiple stages.

To avoid reading incomplete source files, semi-mite introduces a stabilization delay before recompilation.

Workflow:

```text
File Change
      ↓
Debounce Delay
      ↓
Source Scan
      ↓
Compilation
      ↓
Library Reload
      ↓
Binding Refresh
```

This prevents race conditions and incomplete parsing.

---

## C. Advanced Runtime Type Matching

The framework supports runtime matching between Java abstractions and native structures.

Example:

```java
String baseCppType = cppType.replace("*", "").trim();

if (javaClassName.equals(baseCppType)) {
    return true;
}

if (argClass.isArray()
        && argClass.getComponentType().getSimpleName().equals(baseCppType)) {
    return true;
}

if (arg instanceof java.util.Collection) {
    return true;
}
```

Supported mappings:

| Java       | Native    |
| ---------- | --------- |
| User       | User*     |
| User[]     | User**    |
| List<User> | User**    |
| TreeNode   | TreeNode* |

---

## D. Native Optimization Pipeline

Generated libraries are compiled using aggressive optimization flags.

Example:

```bash
-shared
-Ofast
-march=native
-mtune=native
-flto=auto
-funroll-loops
-ffast-math
```

Enabled optimizations include:

* Link-Time Optimization (LTO)
* Loop unrolling
* CPU-specific instruction generation
* SIMD optimizations
* AVX instruction utilization (when available)

---

# 📂 Example Repository

Looking for practical examples?

The companion repository **[https://github.com/krosta12/semi-mite-examples](https://github.com/krosta12/semi-mite-examples)** contains:

* Cyclic graph processing
* Deep object trees
* Off-heap array operations
* Physics simulations
* Matrix processing
* Native analytics workloads

---

## 📄 License

This project is licensed under the Apache License 2.0.

See the [LICENSE](./LICENSE) file for details.
