package io.crashnotifier.domain;

public record PodProblem(
    String podName, String namespace, String problemType,
    String containerName, String imageName, int restartCount,
    String message, String events, String logs
) {}
