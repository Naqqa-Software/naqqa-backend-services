package com.naqqa.outreach.service;

/**
 * Thrown when Apollo rejects a lookup because the account is out of lead credits (HTTP 422
 * "insufficient credits"). The extraction loop catches this to pause enrichment for a while and to
 * return the claimed lead to the pool instead of burning it as NO_EMAIL.
 */
public class ApolloCreditsException extends RuntimeException {
    public ApolloCreditsException(String message) {
        super(message);
    }
}
