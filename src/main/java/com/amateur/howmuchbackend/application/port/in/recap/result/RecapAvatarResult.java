package com.amateur.howmuchbackend.application.port.in.recap.result;

public record RecapAvatarResult(String contentType, byte[] content) {
    public RecapAvatarResult { content = content.clone(); }
    @Override public byte[] content() { return content.clone(); }
}
