package com.redhat.cloud.notifications.routers;

import com.redhat.cloud.notifications.models.CamelProperties;
import com.redhat.cloud.notifications.models.Endpoint;
import com.redhat.cloud.notifications.models.EndpointStatus;
import com.redhat.cloud.notifications.models.EndpointType;
import com.redhat.cloud.notifications.openbridge.Bridge;
import com.redhat.cloud.notifications.openbridge.BridgeApiService;
import com.redhat.cloud.notifications.openbridge.BridgeAuth;
import com.redhat.cloud.notifications.openbridge.Processor;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.transaction.Transactional;
import javax.ws.rs.WebApplicationException;
import java.util.List;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;
import static javax.persistence.LockModeType.PESSIMISTIC_WRITE;
import static org.hibernate.LockOptions.SKIP_LOCKED;

/**
 * Scheduled task to check (OpenBridge) Endpoints
 * for their state. Those endpoints can go from
 * 'accepted' to 'provisioning' and then 'ready' or 'failed'.
 * We need to poll OB regularly to see when they are
 * good and then set the state accordingly.
 */
@ApplicationScoped
public class EndpointReadyChecker {

    @Inject
    EntityManager em;

    @Inject
    @RestClient
    BridgeApiService bridgeApiService;

    @Inject
    Bridge bridge;

    @Inject
    BridgeAuth bridgeAuth;

    String endpointQueryString = "SELECT e FROM Endpoint e " +
            "WHERE e.compositeType.type = :type AND e.compositeType.subType IN (:subTypes) " +
            "AND e.status NOT IN (:ready, :failed) ";

    @Scheduled(concurrentExecution = SKIP, every = "${ob.ready-check.period:10s}")
    @Transactional
    public void execute() {

        List<Endpoint> endpoints = em.createQuery(endpointQueryString, Endpoint.class)
                .setParameter("ready", EndpointStatus.READY)
                .setParameter("failed", EndpointStatus.FAILED)
                .setParameter("type", EndpointType.CAMEL)
                .setParameter("subTypes", "slack")
                // DB rows will be locked when they are processed by this scheduled job, preventing other pods from accessing them.
                .setLockMode(PESSIMISTIC_WRITE)
                // Postgres will skip the DB rows that were locked by another pod so that this scheduled job will never be waiting for locks.
                .setHint("javax.persistence.lock.timeout", SKIP_LOCKED)
                .getResultList();

        for (Endpoint ep : endpoints) {
            CamelProperties cp = em.find(CamelProperties.class, ep.getId()); // TODO Fetch in one go
            String processorId = cp.getExtras().get("processorId");
            try {
                Processor processor = bridgeApiService.getProcessorById(bridge.getId(), processorId, bridgeAuth.getToken());
                String status = processor.getStatus();
                Log.debugf("  Status reported by OB for processor %s : %s", processorId, status);
                if ("ready".equals(status)) {
                    ep.setStatus(EndpointStatus.READY);
                }
                if ("failed".equals(status)) {
                    ep.setStatus(EndpointStatus.FAILED);
                }
            } catch (WebApplicationException wae) {
                Log.warn("Getting data from OB failed", wae);
                ep.setStatus(EndpointStatus.FAILED);
            }
        }
    }
}