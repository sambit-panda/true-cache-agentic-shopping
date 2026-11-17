package agentorders;

import java.util.UUID;

/**
 * One agent conversation, bound to exactly one customer. Identity comes from the authenticated
 * session, never from model-supplied arguments, so an agent cannot read or change another
 * customer's data by asking for it.
 */
public record Session(long customerId, String sessionId) {
    public static Session forCustomer(long customerId) {
        return new Session(customerId, UUID.randomUUID().toString().substring(0, 8));
    }
}
