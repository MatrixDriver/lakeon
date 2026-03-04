package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.repository.DatabaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-time migration: update existing databases' connection_uri to point to proxy external address.
 */
@Component
public class ConnectionUriMigration {
    private static final Logger log = LoggerFactory.getLogger(ConnectionUriMigration.class);

    private final DatabaseRepository databaseRepository;
    private final LakeonProperties props;

    public ConnectionUriMigration(DatabaseRepository databaseRepository, LakeonProperties props) {
        this.databaseRepository = databaseRepository;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateConnectionUris() {
        String host = props.getProxy().getExternalHost();
        int port = props.getProxy().getExternalPort();
        if (host == null || host.isBlank()) {
            host = "proxy.lakeon.svc.cluster.local";
        }

        String proxyAuthority = host + ":" + port;
        List<DatabaseEntity> databases = databaseRepository.findAll();
        int updated = 0;

        for (DatabaseEntity db : databases) {
            String uri = db.getConnectionUri();
            String expectedUri = "postgres://" + db.getDbUser() + "@" + proxyAuthority + "/" + db.getName()
                    + "?options=endpoint%3D" + db.getName();
            if (expectedUri.equals(uri)) {
                continue;
            }
            db.setConnectionUri(expectedUri);
            databaseRepository.save(db);
            updated++;
            log.info("Migrated connection_uri for database {}: {}", db.getId(), expectedUri);
        }

        if (updated > 0) {
            log.info("Migrated connection_uri for {} database(s) to proxy address {}", updated, proxyAuthority);
        }
    }
}
