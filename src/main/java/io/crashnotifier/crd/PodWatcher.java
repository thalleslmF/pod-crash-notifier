package io.crashnotifier.crd;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import io.fabric8.kubernetes.model.annotation.ShortNames;

@Group("crash-notifier.io")
@Version("v1")
@ShortNames("pw")
public class PodWatcher extends CustomResource<PodWatcherSpec, PodWatcherStatus> implements Namespaced {
}
