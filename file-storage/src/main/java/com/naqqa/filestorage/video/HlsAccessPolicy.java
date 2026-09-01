package com.naqqa.filestorage.video;

import org.springframework.security.core.Authentication;

/**
 * Decides who may stream a given ladder.
 *
 * <p>The library will not guess an authorisation model — enrolment, ownership, purchase and role are
 * all application concepts. Implement this and expose it as a bean to enable HLS serving.
 *
 * <p>Called once per segment request, but the result is memoised for
 * {@code gcs.lib.hls.access-cache-ttl-seconds} — a playthrough is hundreds of requests, and without
 * that memo each one would re-run this. Keep implementations cheap and side-effect free anyway.
 *
 * <p>The TTL is also the upper bound on how long revoked access keeps working. Lower it if that
 * matters more than the query load; set it to zero to check every request.
 */
public interface HlsAccessPolicy {

    /**
     * @param groupId        the ladder's identifier, as used in {@code <vodPrefix>/<groupId>/...}
     * @param authentication the caller; never null, the endpoint requires authentication
     * @return true to serve the requested object
     */
    boolean canStream(String groupId, Authentication authentication);
}
