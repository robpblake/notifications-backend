package com.redhat.cloud.notifications.db.repositories;

import com.redhat.cloud.notifications.db.StatelessSessionFactory;
import com.redhat.cloud.notifications.db.converters.EndpointTypeConverter;
import com.redhat.cloud.notifications.db.converters.NotificationHistoryDetailsConverter;
import com.redhat.cloud.notifications.models.Endpoint;
import com.redhat.cloud.notifications.models.NotificationHistory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.NoResultException;
import javax.transaction.Transactional;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class NotificationHistoryRepository {

    @Inject
    StatelessSessionFactory statelessSessionFactory;

    @Transactional
    public void createNotificationHistory(NotificationHistory history) {
        /*
         * The following query contains a subquery that retrieves the ID of the endpoint if it still exists. There's no
         * guarantee the endpoint will still exist in the DB at the time when the history is written. If it's gone, then
         * the subquery will return null.
         */
        String hql = "INSERT INTO notification_history (id, invocation_time, invocation_result, details, event_id, endpoint_type, endpoint_sub_type, created, endpoint_id) " +
                "VALUES (:id, :invocationTime, :invocationResult, :details, :eventId, :endpointType, :endpointSubType, :created, " +
                "(SELECT id FROM endpoints WHERE id = :endpointId))";
        history.prePersist();
        statelessSessionFactory.getCurrentSession().createNativeQuery(hql)
                .setParameter("id", history.getId())
                .setParameter("invocationTime", history.getInvocationTime())
                .setParameter("invocationResult", history.isInvocationResult())
                .setParameter("details", new NotificationHistoryDetailsConverter().convertToDatabaseColumn(history.getDetails()))
                .setParameter("eventId", history.getEvent().getId())
                .setParameter("endpointType", new EndpointTypeConverter().convertToDatabaseColumn(history.getEndpointType()))
                .setParameter("endpointSubType", history.getEndpointSubType())
                .setParameter("created", history.getCreated())
                .setParameter("endpointId", history.getEndpoint().getId())
                .executeUpdate();
    }

    /**
     * Update a stub history item with data we have received from the Camel sender
     *
     * @param jo Map containing the returned data
     * @return Nothing
     * @see com.redhat.cloud.notifications.events.FromCamelHistoryFiller for the source of data
     */
    @Transactional
    public boolean updateHistoryItem(Map<String, Object> jo) {

        String historyId = (String) jo.get("historyId");

        if (historyId == null || historyId.isBlank()) {
            throw new IllegalArgumentException("History Id is null");
        }

        String outcome = (String) jo.get("outcome");
        // TODO NOTIF-636 Remove oldResult after the Eventing team is done integrating with the new way to determine the success.
        boolean oldResult = outcome != null && outcome.startsWith("Success");
        boolean result = oldResult || jo.containsKey("successful") && ((Boolean) jo.get("successful"));
        Map details = (Map) jo.get("details");
        if (!details.containsKey("outcome")) {
            details.put("outcome", outcome);
        }

        Integer duration = (Integer) jo.getOrDefault("duration", 0);

        String updateQuery = "UPDATE NotificationHistory SET details = :details, invocationResult = :result, invocationTime= :invocationTime WHERE id = :id";
        int count = statelessSessionFactory.getCurrentSession().createQuery(updateQuery)
                .setParameter("details", details)
                .setParameter("result", result)
                .setParameter("id", UUID.fromString(historyId))
                .setParameter("invocationTime", (long) duration)
                .executeUpdate();

        if (count == 0) {
            throw new NoResultException("Update returned no rows");
        } else if (count > 1) {
            throw new IllegalStateException("Update count was " + count);
        }

        return true;
    }

    public Endpoint getEndpointForHistoryId(String historyId) {

        String query = "SELECT e from Endpoint e, NotificationHistory h WHERE h.id = :id AND e.id = h.endpoint.id";
        UUID hid = UUID.fromString(historyId);

        try {
            return statelessSessionFactory.getCurrentSession().createQuery(query, Endpoint.class)
                    .setParameter("id", hid)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }
}
