# naqqa-file-storage

GCS-backed file storage for Spring Boot: uploads (multipart, streaming, resumable), signed or public
URLs, orphan collection, and — optionally — adaptive video transcoding and HLS delivery.

Everything beyond the three required properties is **opt-in**. With only those set, this behaves
exactly as a plain single-bucket uploader.

---

## 1. Minimum setup

```properties
gcs.lib.project-id=my-gcp-project
gcs.lib.bucket-name=my-bucket
# Optional: classpath service-account JSON. Omit to use Application Default Credentials,
# which is what a workload running on GCP should do — no key material to ship or rotate.
gcs.lib.config-file=gcp-service-account.json
```

You get `FileStorageService` (upload / finalize / delete / URL), the `/api/files/**` endpoints, and
an hourly collector that removes uploads never linked to anything.

`FileEntity` rows carry `isLinked = false` until your application attaches them to something and
calls `markFilesAsLinked(...)`. Unlinked files older than the grace window are deleted — an upload
that is abandoned halfway does not leak storage forever.

---

## 2. Unique object names

```properties
gcs.lib.unique-object-names=true    # default
```

The object key is `directory/<millis>_<fileName>`. **A millisecond is not unique.** Two uploads of
the same filename into the same directory within the same millisecond produce the same key — the
second overwrites the first, then trips whatever unique constraint your file table has. Any client
that uploads a queue in parallel reaches this; it is not theoretical.

With this on, a random token is inserted before the extension:

```
lesson.mp4  ->  lesson_9f2c1ab47e08d3c6b15a4f72.mp4
```

Only the **stored key** changes. `originalFileName` keeps the user's filename, so the UI is
unaffected. Set to `false` only if you have your own naming scheme.

> This also makes long-lived cache headers safe: content at a key never changes, so
> `Cache-Control: immutable` cannot go stale.

---

## 2b. Large uploads

```properties
gcs.lib.upload.mode=RESUMABLE              # default
gcs.lib.upload.signed-url-expiry-minutes=60  # SIGNED_PUT only
```

`startResumableSession` returns the URL the client uploads to.

**`RESUMABLE` (default)** opens a real GCS resumable session and returns its **session URI**. The
client can send the object in chunks and resume from the last committed byte after a dropped
connection. GCS keeps the session valid for about a week.

**`SIGNED_PUT`** returns one signed PUT for the whole object. Simple, but the entire transfer has to
finish inside the expiry window, and a failure at 95% means starting over. That window is a hard
ceiling on upload size: anything slower than an hour on a 1-hour URL can never complete.

### Client protocol

```
PUT <sessionUri>
Content-Range: bytes 0-8388607/104857600
  -> 308   chunk stored, keep going

PUT <sessionUri>                        # after a failure: where did it get to?
Content-Range: bytes */104857600
  -> 308, Range: bytes=0-8388607        # resume from 8388608

PUT <sessionUri>                        # final chunk
Content-Range: bytes 99614720-104857599/104857600
  -> 200   object committed
```

Chunks other than the last must be a multiple of **256 KiB**. Always resume from the offset GCS
reports, not from what the client believes it sent — a request can fail *after* the bytes were
stored.

### CORS, if the browser uploads directly

Uploading straight to `storage.googleapis.com` keeps the bytes out of your servers entirely. The
bucket must then allow the upload methods and expose the headers the client has to read:

```json
{
  "origin": ["https://app.example.com"],
  "method": ["GET", "HEAD", "PUT", "POST"],
  "responseHeader": ["Content-Type", "Content-Range", "Range", "Location", "X-GUploader-UploadID"],
  "maxAgeSeconds": 3600
}
```

Without `Range` and `Location` exposed the upload still happens, but the browser cannot read the
progress GCS reports, so resuming is impossible.

> A GET/HEAD-only CORS policy silently blocks browser uploads. If yours proxies upload bytes through
> the application instead, that policy is why — and every uploaded byte is then crossing your
> servers, paying for CPU and egress on the way. Going direct is the single biggest win for large
> files.

---

## 3. Public / private bucket split

```properties
gcs.lib.public-bucket-name=my-public-bucket
gcs.lib.public-prefix=public/                   # default
gcs.lib.public-base-url=https://cdn.example.com # optional
```

Keys starting with `public-prefix` are stored in the public bucket and served as **plain unsigned
URLs** — no per-request signBlob round-trip, and cacheable by a CDN. Everything else stays private
and is signed.

The split is decided purely by the directory you upload to:

```java
fileStorageService.uploadFile(file, "public/avatars", userId);   // public bucket, unsigned URL
fileStorageService.uploadFile(file, "private/documents", userId); // private bucket, signed URL
```

Leave `public-bucket-name` blank and every object stays private — safe to deploy before the bucket
exists.

`public-base-url` puts a CDN or load balancer in front. The object key is appended unchanged, so a
path rule matching `public-prefix` maps straight through with **no rewriting**.

> **Careful with `startResumableSession`.** Its parameter order is
> `(directory, fileName, contentType, ownerId)`. All three leading parameters are `String`, so an
> override that names them in a different order still compiles and still satisfies `@Override` —
> while routing on the wrong value. That mistake sends public uploads to the private bucket, where
> they are then served as unsigned URLs that 403.

---

## 4. Video transcoding (optional)

Turns an uploaded master into an adaptive HLS ladder via a **Cloud Run Job**.

```properties
gcs.lib.transcode.enabled=true
gcs.lib.transcode.job-name=transcoder
gcs.lib.transcode.region=europe-west1
gcs.lib.transcode.vod-prefix=vod
gcs.lib.transcode.rungs=1080:4500k:128k,720:2500k:128k,360:800k:64k
gcs.lib.transcode.callback-url=https://api.example.com/api/internal/transcode/callback
gcs.lib.transcode.callback-token=${TRANSCODE_CALLBACK_TOKEN}
```

### Why bother

Serving a raw 4K upload to a phone costs roughly 4–7× the egress of a right-sized rendition, and the
viewer has no lower rung to fall back to on a weak connection. Encoding is a one-time cost of cents;
the egress saving repays it on the **first view**.

### Deploying the job

The job image lives in [`transcoder/`](transcoder/). Build and deploy it once:

```bash
gcloud builds submit transcoder/ --tag $REGION-docker.pkg.dev/$PROJECT/$REPO/transcoder:latest

gcloud run jobs deploy transcoder \
    --image $REGION-docker.pkg.dev/$PROJECT/$REPO/transcoder:latest \
    --region $REGION --cpu 4 --memory 16Gi \
    --task-timeout 7200s --max-retries 2 --parallelism 4 \
    --service-account $SA
```

**Sizing.** Jobs bill only while running, so this is per-encode, not a standing cost. 4 vCPU is
close to cost-neutral against 2 — encoding parallelises, so total vCPU-seconds are similar while
wall time drops. Memory is a **capacity limit, not padding**: `/tmp` is tmpfs backed by instance
memory and must hold the downloaded master *and* the ladder written beside it. A 40-minute 4K master
is ~10 GB and its ladder ~2.6 GB, which does not fit in 8 GiB. `--parallelism` is your cost guard
against a bulk upload starting hundreds of concurrent encodes.

**IAM — the one that bites.** The calling service account needs
`run.jobs.runWithOverrides`, which is **not** in `roles/run.invoker`. An invoker-only binding looks
right and fails at runtime with 403. Grant `roles/run.developer` scoped to the job, or a custom role
containing both `run.jobs.run` and `run.jobs.runWithOverrides`.

### Submitting

```java
videoTranscodeService.submit(masterFile.getFileName(), "course-42-lesson-7");
```

`submit` **never throws** — a failed submit returns `false` and logs. Failing an organizer's upload
because a downstream job is unavailable would be worse than serving the master. Record the
un-started state and retry on a timer; nothing retries internally.

### Receiving the result

```java
@Component
class MyTranscodeHandler implements TranscodeResultHandler {
    public void onTranscodeComplete(String correlationId, boolean success, String detail) {
        // correlationId is the groupId you passed to submit(...)
    }
}
```

With that bean present the callback endpoint is registered at
`gcs.lib.transcode.callback-path`. **Permit that path in your security configuration** — it is
authenticated by the shared secret, not the filter chain. When `callback-token` is blank the
endpoint refuses every request rather than defaulting to open.

The secret travels in an `X-Transcode-Token` header, **not** `Authorization: Bearer`. If your
application runs an OAuth2 resource server, a Bearer header is parsed as a JWT, fails validation and
is rejected with 401 before the endpoint is reached — even on a permitted path. A dedicated header
sidesteps the JWT filter entirely.

Omit the handler bean (or set `expose-callback-endpoint=false`) to handle the callback yourself.

### The ladder

Rungs are `height:videoBitrate:audioBitrate`. Height applies to the **short side**, so a
2160×3840 portrait source yields 1080×1920 — not the 607×1080 sliver a landscape-shaped
`scale=-2:1080` would produce. Rungs above the source are skipped rather than upscaled.

Output is published **atomically**: written to a `.staging-*` prefix and promoted only once every
rung succeeds, so a retried job cannot leave a half-written manifest a player would try to stream.

---

## 5. HLS delivery (optional)

```properties
gcs.lib.hls.enabled=true
gcs.lib.hls.base-path=/api/media
gcs.lib.hls.access-cache-ttl-seconds=120
```

Serves the ladder back out through your application, under **your** access rules:

```java
@Component
class MyAccessPolicy implements HlsAccessPolicy {
    public boolean canStream(String groupId, Authentication auth) {
        return enrolmentService.isEnrolled(auth.getName(), groupId);
    }
}
```

Without that bean, HLS serving is not registered at all — the library will not invent an
authorisation model for private video.

Requests look like `GET {base-path}/{groupId}/hls/master.m3u8`. ffmpeg emits **relative**
references throughout, so no manifest rewriting is needed.

**Why the cache exists.** A playthrough is hundreds of segment requests. Without memoisation each
one re-runs your policy — a 30-minute video becomes ~300 identical queries. The TTL is also the
upper bound on how long revoked access keeps working; set it to `0` to check every request.

**Why no `Range` handling.** Segments are small whole-object fetches. Each request is short and
releases its server slot in about a second, instead of one request holding a slot for the length of
the video — which also sidesteps request-timeout limits that progressive streaming of a long file
can hit.

### Serving from a CDN instead

```properties
gcs.lib.hls.cdn.enabled=true
gcs.lib.hls.cdn.base-url=https://app.example.com
gcs.lib.hls.cdn.key-name=vod-key-1
gcs.lib.hls.cdn.key-value=${CDN_SIGNING_KEY}
gcs.lib.hls.cdn.cookie-ttl-seconds=300
```

Serving through the application means every video byte crosses your servers. With this enabled the
application authorises **once** and hands out a signed cookie; the CDN validates it at the edge and
segments never reach you again.

`CdnSignedCookieService` mints the cookie. Your endpoint checks entitlement, then:

```java
if (cdnCookies.isEnabled()) {
    response.addHeader(HttpHeaders.SET_COOKIE,
        "Cloud-CDN-Cookie=" + cdnCookies.sign(groupId)
            + "; Path=" + cdnCookies.cookiePath(groupId)   // scoped to ONE ladder
            + "; Max-Age=" + cdnCookies.ttlSeconds()
            + "; HttpOnly; Secure; SameSite=Lax");
    return cdnCookies.masterPlaylistUrl(groupId);
}
```

**The path prefix is the security boundary.** A cookie signed for `/vod/42/` is rejected for
`/vod/43/`, so entitlement to one item cannot reach another. That is why each ladder gets its own
directory.

**The TTL is your revocation window.** Once issued, the edge cannot learn that access was
withdrawn — expiry is the only bound. Keep it to minutes and have the player re-request as it
plays, rather than the hours that are common elsewhere. Each refresh is one entitlement check.

### Origin setup

The origin bucket **stays private**. Grant read to Cloud CDN's cache-fill service account:

```bash
gcloud compute backend-buckets create bb-vod --gcs-bucket-name=$BUCKET --enable-cdn
gcloud compute backend-buckets add-signed-url-key bb-vod --key-name=vod-key-1 --key-file=key.txt

gcloud storage buckets add-iam-policy-binding gs://$BUCKET \
  --member="serviceAccount:service-$PROJECT_NUMBER@cloud-cdn-fill.iam.gserviceaccount.com" \
  --role="roles/storage.objectViewer"
```

Then route your `vod/` prefix to that backend bucket in the URL map. Nothing is granted to
`allUsers`; an unsigned request is refused at the edge.

> **Telling the two 403s apart.** If a correctly signed request still returns 403, check the body.
> An XML `AccessDenied` is *Cloud Storage* refusing the origin fetch — the signature was fine and
> the cache-fill grant is missing. A plain 403 with no body is the *edge* rejecting the signature.

Switching this off falls back to serving through the application, so it is a config change rather
than a deploy.

## 6. Component scanning

Consumers scan the library's package:

```java
@SpringBootApplication(scanBasePackages = {"com.example.app", "com.naqqa.filestorage"})
```

That is how `FilesController` and the optional endpoints are registered. Because they arrive by
**scanning** rather than auto-configuration, their feature flags live as `@ConditionalOnProperty` on
the classes themselves — a condition placed only in an auto-configuration class would not be
consulted at all, and the endpoint would register regardless of the flag.

Also register the entity and repository:

```java
@EntityScan("com.naqqa.filestorage.entities")
@EnableJpaRepositories("com.naqqa.filestorage.repository")
```

> **Bean-name collisions.** The library exposes a bean named `videoTranscodeService`. A class of the
> same simple name in your application gets the same default bean name and one silently displaces the
> other — with a confusing "no qualifying bean of type X" failure elsewhere. Name application classes
> distinctly (e.g. `LessonTranscodeService`).

## 7. Replacing any piece

Every bean is `@ConditionalOnMissingBean`. Declare your own and the library steps aside:

```java
@Bean
FileStorageService fileStorageService(FileRepository repo, Storage storage, FileStorageProperties p) {
    return new MyFileStorageService(repo, storage, p);
}
```

## 8. Property reference

| Property | Default | Purpose |
|---|---|---|
| `gcs.lib.project-id` | — | **Required.** GCP project |
| `gcs.lib.bucket-name` | — | **Required.** Private bucket |
| `gcs.lib.config-file` | *(blank)* | Classpath SA key; blank = ADC |
| `gcs.lib.public-bucket-name` | *(blank)* | Enables the bucket split |
| `gcs.lib.public-prefix` | `public/` | Key prefix routed to the public bucket |
| `gcs.lib.public-base-url` | *(blank)* | CDN/LB origin for public objects |
| `gcs.lib.unique-object-names` | `true` | Collision-proof object keys |
| `gcs.lib.upload.mode` | `RESUMABLE` | `RESUMABLE` or `SIGNED_PUT` |
| `gcs.lib.upload.signed-url-expiry-minutes` | `60` | `SIGNED_PUT` window |
| `gcs.lib.gc-batch-size` | `500` | Orphans collected per pass |
| `gcs.lib.gc-grace-hours` | `24` | Age before an unlinked upload is collected |
| `gcs.lib.transcode.enabled` | `false` | Enable transcoding |
| `gcs.lib.transcode.job-name` | `transcoder` | Cloud Run Job name |
| `gcs.lib.transcode.region` | `europe-west1` | Job region |
| `gcs.lib.transcode.project-id` | *(inherits)* | Override project for the job |
| `gcs.lib.transcode.vod-prefix` | `vod` | Ladder output root |
| `gcs.lib.transcode.rungs` | 1080/720/360 | `height:vBitrate:aBitrate` |
| `gcs.lib.transcode.callback-url` | *(blank)* | Blank disables the callback |
| `gcs.lib.transcode.callback-token` | *(blank)* | **Blank = endpoint refuses everything** |
| `gcs.lib.transcode.expose-callback-endpoint` | `true` | Register the built-in endpoint |
| `gcs.lib.transcode.callback-path` | `/api/internal/transcode/callback` | Callback path |
| `gcs.lib.hls.enabled` | `false` | Enable HLS serving |
| `gcs.lib.hls.base-path` | `/api/media` | HLS endpoint base |
| `gcs.lib.hls.access-cache-ttl-seconds` | `120` | Memo TTL; `0` disables |
| `gcs.lib.hls.access-cache-max-entries` | `50000` | Sweep threshold |
| `gcs.lib.hls.cdn.enabled` | `false` | Serve the ladder from Cloud CDN |
| `gcs.lib.hls.cdn.base-url` | *(blank)* | Origin the ladder is served from |
| `gcs.lib.hls.cdn.key-name` | *(blank)* | Signed-URL key name on the backend bucket |
| `gcs.lib.hls.cdn.key-value` | *(blank)* | Same key material, base64url |
| `gcs.lib.hls.cdn.cookie-ttl-seconds` | `300` | Cookie life = revocation window |
