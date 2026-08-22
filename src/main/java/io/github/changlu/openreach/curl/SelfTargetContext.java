package io.github.changlu.openreach.curl;

/**
 * Inbound OpenReach address information used to make sure the outbound curl
 * capability cannot call the same OpenReach service back through its public
 * hostname, reverse proxy hostname or local interface address.
 */
public record SelfTargetContext(
        String serverName,
        String localName,
        String localAddr,
        String hostHeader
) {}
