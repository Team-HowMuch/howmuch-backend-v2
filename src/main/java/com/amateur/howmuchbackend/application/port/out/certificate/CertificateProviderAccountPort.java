package com.amateur.howmuchbackend.application.port.out.certificate;

import com.amateur.howmuchbackend.application.certificate.model.*;

public interface CertificateProviderAccountPort {
    boolean delete(String organization, String connectedId);

    CertificateProviderResult register(
            String organization, CertificateMaterial material, boolean includeAccountCredentials);

    CertificateProviderResult add(
            String connectedId,
            String organization,
            CertificateMaterial material,
            boolean includeAccountCredentials);

    CertificateProviderResult update(
            String connectedId,
            String organization,
            CertificateMaterial material,
            boolean includeAccountCredentials);
}
