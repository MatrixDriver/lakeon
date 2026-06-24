package com.lakeon.pageserver;

import java.util.List;

@FunctionalInterface
public interface PageserverNodeProvider {
    List<PageserverNode> nodes();
}
