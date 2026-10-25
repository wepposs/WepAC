package dev.weposs.wepac.ai;

import java.util.UUID;

/**
 * One labelled training example: raw feature vector + label (1 = cheater, 0 = legit).
 * {@code owner} tracks which player donated the sample so that legit donations
 * can be PURGED if the donor is later punished (anti-poisoning feedback loop).
 */
public final class TrainingSample {

    public final double[] features;
    public final double label;
    public final long createdAt;
    public final UUID owner; // nullable

    public TrainingSample(double[] features, double label) {
        this(features, label, null);
    }

    public TrainingSample(double[] features, double label, UUID owner) {
        this.features = features;
        this.label = label;
        this.owner = owner;
        this.createdAt = System.currentTimeMillis();
    }

    public TrainingSample(double[] features, double label, UUID owner, long createdAt) {
        this.features = features;
        this.label = label;
        this.owner = owner;
        this.createdAt = createdAt;
    }
}
