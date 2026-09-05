package com.example.smartcropapp.nalaros;

public class Artifact {

    private final String type;
    private final String location;
    private final long size;
    private final String fingerprint;
    private final String producer;
    private boolean valid;

    public Artifact(
            String type,
            String location,
            long size,
            String fingerprint,
            String producer,
            boolean valid) {

        this.type = type;
        this.location = location;
        this.size = size;
        this.fingerprint = fingerprint;
        this.producer = producer;
        this.valid = valid;
    }

    public String getType() {
        return type;
    }

    public String getLocation() {
        return location;
    }

    public long getSize() {
        return size;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getProducer() {
        return producer;
    }

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }
}
