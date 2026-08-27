package com.amateur.howmuchbackend.application.account;

import com.amateur.howmuchbackend.application.port.out.external.BankTransactionPort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class AccountTransactionKey {
    private AccountTransactionKey() {
    }

    public static String from(
            String accountKey,
            BankTransactionPort.BankTransaction transaction
    ) {
        String source = String.join("\u0000",
                accountKey,
                transaction.transactedAt().toString(),
                transaction.type().name(),
                transaction.amount().stripTrailingZeros().toPlainString(),
                transaction.afterBalance() == null
                        ? ""
                        : transaction.afterBalance().stripTrailingZeros().toPlainString(),
                transaction.description() == null ? "" : transaction.description()
        );
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available.", impossible);
        }
    }
}
