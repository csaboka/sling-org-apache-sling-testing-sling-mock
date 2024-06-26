/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.testing.mock.sling.context;

import java.util.UUID;

import org.apache.jackrabbit.JcrConstants;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.testing.mock.sling.builder.ImmutableValueMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.annotation.versioning.ConsumerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages unique root paths in JCR repository.
 * This is important for resource resolver types like JCR_JACKRABBIT
 * where the repository is not cleaned for each test run. This class provides
 * unique root paths for each run, and cleans them up when done.
 */
@ConsumerType
public class UniqueRoot {

    private final SlingContextImpl context;

    /**
     * Unique path part
     */
    protected final String uniquePathPart;

    private Resource contentRoot;
    private Resource appsRoot;
    private Resource libsRoot;

    private static final Logger log = LoggerFactory.getLogger(UniqueRoot.class);

    /**
     * @param context Sling context
     */
    protected UniqueRoot(@NotNull SlingContextImpl context) {
        this.context = context;
        // generate unique path part by using a UUID
        uniquePathPart = UUID.randomUUID().toString();
    }

    /**
     * Get or create resource with given JCR primary type
     * @param path Path
     * @param primaryType JCR primary type
     * @return Resource (never null)
     */
    protected final Resource getOrCreateResource(@NotNull String path, @NotNull String primaryType) {
        try {
            return ResourceUtil.getOrCreateResource(
                    context.resourceResolver(),
                    path,
                    ImmutableValueMap.of(JcrConstants.JCR_PRIMARYTYPE, primaryType),
                    null,
                    true);
        } catch (PersistenceException ex) {
            throw new RuntimeException("Unable to create resource at " + path + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Gets (and creates if required) a unique path at <code>/content/xxx</code>.
     * The path (incl. all children) is automatically removed when the unit test completes.
     * @return Unique content path
     */
    public final @NotNull String content() {
        if (contentRoot == null) {
            contentRoot = getOrCreateResource("/content/" + uniquePathPart, "sling:OrderedFolder");
        }
        return contentRoot.getPath();
    }

    /**
     * Gets (and creates if required) a unique path at <code>/apps/xxx</code>.
     * The path (incl. all children) is automatically removed when the unit test completes.
     * @return Unique content path
     */
    public final @NotNull String apps() {
        if (appsRoot == null) {
            appsRoot = getOrCreateResource("/apps/" + uniquePathPart, "sling:OrderedFolder");
        }
        return appsRoot.getPath();
    }

    /**
     * Gets (and creates if required) a unique path at <code>/libs/xxx</code>.
     * The path (incl. all children) is automatically removed when the unit test completes.
     * @return Unique content path
     */
    public final @NotNull String libs() {
        if (libsRoot == null) {
            libsRoot = getOrCreateResource("/libs/" + uniquePathPart, "sling:OrderedFolder");
        }
        return libsRoot.getPath();
    }

    /**
     * Cleanup is called when the unit test rule completes a unit test run.
     * All resources created have to be removed.
     */
    protected void cleanUp() {
        deleteResources(contentRoot, appsRoot, libsRoot);
    }

    /**
     * Deletes the given set of resources and commits afterwards.
     * @param resources Resources to be deleted
     */
    protected final void deleteResources(@Nullable Resource @NotNull ... resources) {
        for (Resource resource : resources) {
            if (resource != null && context.resourceResolver.getResource(resource.getPath()) != null) {
                try {
                    context.resourceResolver().delete(resource);
                } catch (PersistenceException ex) {
                    log.warn("Unable to delete root path " + resource.getPath(), ex);
                }
            }
        }
        try {
            context.resourceResolver().commit();
        } catch (PersistenceException ex) {
            log.warn("Unable to commit root path deletions.", ex);
        }
    }
}
