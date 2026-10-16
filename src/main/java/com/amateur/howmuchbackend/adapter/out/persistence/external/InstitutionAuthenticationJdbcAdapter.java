package com.amateur.howmuchbackend.adapter.out.persistence.external;

import com.amateur.howmuchbackend.application.port.out.external.InstitutionAuthenticationStorePort;
import com.amateur.howmuchbackend.domain.external.BankAuthenticationMethod;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class InstitutionAuthenticationJdbcAdapter implements InstitutionAuthenticationStorePort {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public List<String> findCertificateOrganizations(long connectionId) {
        if (!schemaAvailable()) return List.of();
        return jdbc.queryForList(
                """
SELECT organization FROM connected_institutions
WHERE external_connection_id=? AND status='CONNECTED' AND authentication_method='CERTIFICATE'
ORDER BY organization
""",
                String.class,
                connectionId);
    }

    public void recordMethod(
            long connectionId, String organization, BankAuthenticationMethod method) {
        // Rolling-deployment compatibility: the existing login paths still work before this
        // optional schema is applied.
        // Reference feature startup requires these columns before its APIs are activated.
        if (!schemaAvailable()) return;
        int updated =
                jdbc.update(
                        """
UPDATE connected_institutions SET authentication_method=?,authentication_method_confirmed_at=?
WHERE external_connection_id=? AND organization=?
""",
                        method.name(),
                        method == BankAuthenticationMethod.UNKNOWN
                                ? null
                                : LocalDateTime.now(clock),
                        connectionId,
                        organization);
        if (updated != 1)
            throw new IllegalStateException(
                    "Connected institution required before recording authentication method");
    }

    private boolean schemaAvailable() {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
SELECT count(*)=2 FROM pg_attribute WHERE attrelid=to_regclass('connected_institutions')
AND attname IN ('authentication_method','authentication_method_confirmed_at') AND NOT attisdropped
""",
                        Boolean.class));
    }
}
