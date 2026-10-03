# naqqa-storage

`com.naqqa:naqqa-storage` — a small Spring Boot library for **Google Cloud Storage** with built-in
**image optimization**. Datastore-agnostic: it persists nothing, so the consuming app keeps file
metadata in its own store (JPA, MongoDB, …).

## What it gives you (auto-configured beans)

- **`NaqqaStorage`** — object storage over two buckets:
  - a **public** bucket: objects are written `publicRead` with a one-year immutable cache and served by a
    stable `publicUrl(key)` (`https://storage.googleapis.com/<public-bucket>/<key>`);
  - a **private** bucket: objects are written with no public ACL and read back only via a short-lived
    **V4 `signedUrl(key, ttl)`** (works offline from the service-account key). Use for invoices / per-user files.
  - `upload`, `download` (searches both tiers), `exists`, `delete`, `visibilityOf`.
- **`ImageOptimizer`** — turns a raster image (JPEG/PNG/WEBP/BMP) into responsive **WebP** variants
  (configurable widths + an optional full-size), honouring EXIF orientation, bounded by a concurrency
  semaphore. It only encodes bytes — you decide where/how to store and map the variants.
- **`ContentTypeSniffer`** — magic-byte MIME detection (incl. `application/pdf`) for objects with no
  stored content type (e.g. during migration).

## Configuration (`naqqa.storage.*`)

```properties
naqqa.storage.gcs.project-id=alert-library-360220
naqqa.storage.gcs.credentials-location=file:/etc/omy/gcs-key.json   # or classpath:... ; empty => ADC
naqqa.storage.gcs.public-bucket=omy-public
naqqa.storage.gcs.private-bucket=omy-private
naqqa.storage.gcs.public-base-url=https://storage.googleapis.com
naqqa.storage.gcs.signed-url-ttl-seconds=300

naqqa.storage.image.enabled=true
naqqa.storage.image.quality=82
naqqa.storage.image.widths=480,960,1600
naqqa.storage.image.full-size-max-width=2400
naqqa.storage.image.max-concurrent=2
```

The buckets must be **fine-grained** (not Uniform Bucket-Level Access) for the public `publicRead` ACL
to apply; the private bucket should have public-access-prevention enforced.

## Consume it

Pure autoconfiguration — just add the dependency (resolved from GitHub Packages). No component scan or
entity scan needed.

```xml
<dependency>
    <groupId>com.naqqa</groupId>
    <artifactId>naqqa-storage</artifactId>
    <version>${naqqa-storage.version}</version>
</dependency>
```

## Build & publish

```bash
mvn -B clean install              # local
mvn -B clean deploy -DskipTests   # GitHub Packages (server id `github` in ~/.m2/settings.xml)
```
