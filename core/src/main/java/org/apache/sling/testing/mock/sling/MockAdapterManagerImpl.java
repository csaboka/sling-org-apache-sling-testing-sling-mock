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
package org.apache.sling.testing.mock.sling;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.sling.adapter.Adaption;
import org.apache.sling.adapter.internal.AdaptionImpl;
import org.apache.sling.api.SlingConstants;
import org.apache.sling.api.adapter.AdapterFactory;
import org.apache.sling.api.adapter.AdapterManager;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.jetbrains.annotations.NotNull;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.sling.api.adapter.AdapterFactory.ADAPTABLE_CLASSES;
import static org.apache.sling.api.adapter.AdapterFactory.ADAPTER_CLASSES;

/**
 * This is a copy of org.apache.sling.adapter.internal.AdpaterManagerImpl from Sling Adapter 2.1.6,
 * with all calls to SyntheticResource.setAdapterManager/unsetAdapterManager disabled, because this would
 * break the {@link ThreadsafeMockAdapterManagerWrapper} concept.
 * Additionally the reference to PackageAdmin is disabled.
 */
@Component(
        immediate = true,
        service = AdapterManager.class,
        property = {
            Constants.SERVICE_DESCRIPTION + "=Sling Adapter Manager",
            Constants.SERVICE_VENDOR + "=The Apache Software Foundation"
        },
        reference =
                @Reference(
                        name = "AdapterFactory",
                        service = AdapterFactory.class,
                        cardinality = ReferenceCardinality.MULTIPLE,
                        policy = ReferencePolicy.DYNAMIC,
                        bind = "bindAdapterFactory",
                        unbind = "unbindAdapterFactory"))
public class MockAdapterManagerImpl implements AdapterManager {

    private final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * The OSGi <code>ComponentContext</code> to retrieve
     * {@link AdapterFactory} service instances.
     */
    private volatile ComponentContext context;

    /**
     * A list of {@link AdapterFactory} services bound to this manager before
     * the manager has been activated. These bound services will be accessed as
     * soon as the manager is being activated.
     */
    private final List<ServiceReference<AdapterFactory>> boundAdapterFactories =
            new LinkedList<ServiceReference<AdapterFactory>>();

    /**
     * A map of {@link AdapterFactoryDescriptorMap} instances. The map is
     * indexed by the fully qualified class names listed in the
     * {@link AdapterFactory#ADAPTABLE_CLASSES} property of the
     * {@link AdapterFactory} services.
     *
     * @see AdapterFactoryDescriptorMap
     */
    private final Map<String, AdapterFactoryDescriptorMap> descriptors =
            new HashMap<String, AdapterFactoryDescriptorMap>();

    /**
     * Matrix of {@link AdapterFactoryDescriptor} instances primarily indexed by the fully
     * qualified name of the class to be adapted and secondarily indexed by the
     * fully qualified name of the class to adapt to (the target class).
     * <p>
     * This cache is built on demand by calling the
     * {@link #getAdapterFactories(Class)} method. It is cleared
     * whenever an adapter factory is registered on unregistered.
     */
    private final ConcurrentMap<String, Map<String, List<AdapterFactoryDescriptor>>> factoryCache =
            new ConcurrentHashMap<String, Map<String, List<AdapterFactoryDescriptor>>>();

    // DISABLED IN THIS COPY OF CLASS
    /*
    @Reference
    private PackageAdmin packageAdmin;
    */

    // ---------- AdapterManager interface -------------------------------------

    /**
     * Returns the adapted <code>adaptable</code> or <code>null</code> if
     * the object cannot be adapted.
     *
     * @see org.apache.sling.api.adapter.AdapterManager#getAdapter(java.lang.Object, java.lang.Class)
     */
    @Override
    @SuppressWarnings("null")
    public <AdapterType> AdapterType getAdapter(
            @NotNull final Object adaptable, @NotNull final Class<AdapterType> type) {

        // get the adapter factories for the type of adaptable object
        final Map<String, List<AdapterFactoryDescriptor>> factories = getAdapterFactories(adaptable.getClass());

        // get the factory for the target type
        final List<AdapterFactoryDescriptor> descList = factories.get(type.getName());

        if (descList != null && descList.size() > 0) {
            for (AdapterFactoryDescriptor desc : descList) {
                final AdapterFactory factory = desc == null ? null : desc.getFactory();

                // have the factory adapt the adaptable if the factory exists
                if (factory != null) {
                    log.debug("Trying adapter factory {} to map {} to {}", new Object[] {factory, adaptable, type});

                    AdapterType adaptedObject = factory.getAdapter(adaptable, type);
                    if (adaptedObject != null) {
                        log.debug("Using adapter factory {} to map {} to {}", new Object[] {factory, adaptable, type});
                        return adaptedObject;
                    }
                }
            }
        }

        // no factory has been found, so we cannot adapt
        log.debug("No adapter factory found to map {} to {}", adaptable, type);

        return null;
    }

    // ----------- SCR integration ---------------------------------------------

    /**
     * Activate the manager.
     * Bind all already registered factories
     * @param context Component context
     */
    protected void activate(final ComponentContext context) {
        this.context = context;

        // register all adapter factories bound before activation
        final List<ServiceReference<AdapterFactory>> refs;
        synchronized (this.boundAdapterFactories) {
            refs = new ArrayList<ServiceReference<AdapterFactory>>(this.boundAdapterFactories);
            boundAdapterFactories.clear();
        }
        for (final ServiceReference<AdapterFactory> reference : refs) {
            registerAdapterFactory(context, reference);
        }

        // final "enable" this manager by setting the instance
        // DISABLED IN THIS COPY OF CLASS
        // SyntheticResource.setAdapterManager(this);
    }

    /**
     * Deactivate
     * @param context Not used
     */
    protected void deactivate(final ComponentContext context) {
        // DISABLED IN THIS COPY OF CLASS
        // SyntheticResource.unsetAdapterManager(this);
        this.context = null;
    }

    /**
     * Bind a new adapter factory.
     * @param reference Service reference
     */
    protected void bindAdapterFactory(final ServiceReference<AdapterFactory> reference) {
        boolean create = true;
        if (context == null) {
            synchronized (this.boundAdapterFactories) {
                if (context == null) {
                    boundAdapterFactories.add(reference);
                    create = false;
                }
            }
        }
        if (create) {
            registerAdapterFactory(context, reference);
        }
    }

    /**
     * Unbind a adapter factory.
     * @param reference Service reference
     */
    protected void unbindAdapterFactory(final ServiceReference reference) {
        unregisterAdapterFactory(reference);
    }

    // ---------- unit testing stuff only --------------------------------------

    /**
     * Returns the active adapter factories of this manager.
     * <p>
     * <strong><em>THIS METHOD IS FOR UNIT TESTING ONLY. IT MAY BE REMOVED OR
     * MODIFIED WITHOUT NOTICE.</em></strong>
     */
    Map<String, AdapterFactoryDescriptorMap> getFactories() {
        return descriptors;
    }

    /**
     * Returns the current adapter factory cache.
     * <p>
     * <strong><em>THIS METHOD IS FOR UNIT TESTING ONLY. IT MAY BE REMOVED OR
     * MODIFIED WITHOUT NOTICE.</em></strong>
     */
    Map<String, Map<String, List<AdapterFactoryDescriptor>>> getFactoryCache() {
        return factoryCache;
    }

    /**
     * Unregisters the {@link AdapterFactory} referred to by the service
     * <code>reference</code> from the registry.
     */
    @SuppressWarnings("null")
    private void registerAdapterFactory(
            final ComponentContext context, final ServiceReference<AdapterFactory> reference) {
        final String[] adaptables = PropertiesUtil.toStringArray(reference.getProperty(ADAPTABLE_CLASSES));
        final String[] adapters = PropertiesUtil.toStringArray(reference.getProperty(ADAPTER_CLASSES));

        if (adaptables == null || adaptables.length == 0 || adapters == null || adapters.length == 0) {
            return;
        }

        // DISABLED IN THIS COPY OF CLASS
        /*
        for (String clazz : adaptables) {
            if (!checkPackage(packageAdmin, clazz)) {
                log.warn("Adaptable class {} in factory service {} is not in an exported package.", clazz, reference.getProperty(Constants.SERVICE_ID));
            }
        }

        for (String clazz : adapters) {
            if (!checkPackage(packageAdmin, clazz)) {
                log.warn("Adapter class {} in factory service {} is not in an exported package.", clazz, reference.getProperty(Constants.SERVICE_ID));
            }
        }
        */

        final AdapterFactoryDescriptor factoryDesc = new AdapterFactoryDescriptor(context, reference, adapters);

        for (final String adaptable : adaptables) {
            AdapterFactoryDescriptorMap adfMap = null;
            synchronized (this.descriptors) {
                adfMap = descriptors.get(adaptable);
                if (adfMap == null) {
                    adfMap = new AdapterFactoryDescriptorMap();
                    descriptors.put(adaptable, adfMap);
                }
            }
            synchronized (adfMap) {
                adfMap.put(reference, factoryDesc);
            }
        }

        // clear the factory cache to force rebuild on next access
        this.factoryCache.clear();

        // register adaption
        final Dictionary<String, Object> props = new Hashtable<String, Object>();
        props.put(SlingConstants.PROPERTY_ADAPTABLE_CLASSES, adaptables);
        props.put(SlingConstants.PROPERTY_ADAPTER_CLASSES, adapters);

        ServiceRegistration<Adaption> adaptionRegistration =
                this.context.getBundleContext().registerService(Adaption.class, AdaptionImpl.INSTANCE, props);
        if (log.isDebugEnabled()) {
            log.debug("Registered service {} with {} : {} and {} : {}", new Object[] {
                Adaption.class.getName(),
                SlingConstants.PROPERTY_ADAPTABLE_CLASSES,
                Arrays.toString(adaptables),
                SlingConstants.PROPERTY_ADAPTER_CLASSES,
                Arrays.toString(adapters)
            });
        }
        factoryDesc.setAdaption(adaptionRegistration);
    }

    static String getPackageName(String clazz) {
        final int lastDot = clazz.lastIndexOf('.');
        return lastDot <= 0 ? "" : clazz.substring(0, lastDot);
    }

    /**
     * Check that the package containing the class is exported or is a java.*
     * class.
     *
     * @param packageAdmin the PackageAdmin service
     * @param clazz the class name
     * @return true if the package is exported
     */
    // DISABLED IN THIS COPY OF CLASS
    /*
    static boolean checkPackage(PackageAdmin packageAdmin, String clazz) {
        final String packageName = getPackageName(clazz);
        if (packageName.startsWith("java.")) {
            return true;
        }
        return packageAdmin.getExportedPackage(packageName) != null;
    }
    */

    /**
     * Unregisters the {@link AdapterFactory} referred to by the service
     * <code>reference</code> from the registry.
     */
    private void unregisterAdapterFactory(final ServiceReference reference) {
        synchronized (this.boundAdapterFactories) {
            boundAdapterFactories.remove(reference);
        }
        final String[] adaptables = PropertiesUtil.toStringArray(reference.getProperty(ADAPTABLE_CLASSES));
        final String[] adapters = PropertiesUtil.toStringArray(reference.getProperty(ADAPTER_CLASSES));

        if (adaptables == null || adaptables.length == 0 || adapters == null || adapters.length == 0) {
            return;
        }

        boolean factoriesModified = false;
        AdapterFactoryDescriptorMap adfMap = null;

        AdapterFactoryDescriptor removedDescriptor = null;
        for (final String adaptable : adaptables) {
            synchronized (this.descriptors) {
                adfMap = this.descriptors.get(adaptable);
            }
            if (adfMap != null) {
                synchronized (adfMap) {
                    AdapterFactoryDescriptor factoryDesc = adfMap.remove(reference);
                    if (factoryDesc != null) {
                        factoriesModified = true;
                        // A single ServiceReference should correspond to a single Adaption service being registered
                        // Since the code paths above does not fully guarantee it though, let's keep this check in place
                        if (removedDescriptor != null && removedDescriptor != factoryDesc) {
                            log.error(
                                    "When unregistering reference {} got duplicate service descriptors {} and {}. Unregistration of {} services may be incomplete.",
                                    new Object[] {reference, removedDescriptor, factoryDesc, Adaption.class.getName()});
                        }
                        removedDescriptor = factoryDesc;
                    }
                }
            }
        }

        // only remove cache if some adapter factories have actually been
        // removed
        if (factoriesModified) {
            this.factoryCache.clear();
        }

        // unregister adaption
        if (removedDescriptor != null) {
            removedDescriptor.getAdaption().unregister();
            if (log.isDebugEnabled()) {
                log.debug("Unregistered service {} with {} : {} and {} : {}", new Object[] {
                    Adaption.class.getName(),
                    SlingConstants.PROPERTY_ADAPTABLE_CLASSES,
                    Arrays.toString(adaptables),
                    SlingConstants.PROPERTY_ADAPTER_CLASSES,
                    Arrays.toString(adapters)
                });
            }
        }
    }

    /**
     * Returns the map of adapter factories index by adapter (target) class name
     * for the given adaptable <code>clazz</code>. If no adapter exists for
     * the <code>clazz</code> and empty map is returned.
     *
     * @param clazz The adaptable <code>Class</code> for which to return the
     *            adapter factory map by target class name.
     * @return The map of adapter factories by target class name. The map may be
     *         empty if there is no adapter factory for the adaptable
     *         <code>clazz</code>.
     */
    private Map<String, List<AdapterFactoryDescriptor>> getAdapterFactories(final Class<?> clazz) {
        final String className = clazz.getName();
        Map<String, List<AdapterFactoryDescriptor>> entry = this.factoryCache.get(className);
        if (entry == null) {
            // create entry
            entry = createAdapterFactoryMap(clazz);
            this.factoryCache.put(className, entry);
        }

        return entry;
    }

    /**
     * Creates a new target adapter factory map for the given <code>clazz</code>.
     * First all factories defined to support the adaptable class by
     * registration are taken. Next all factories for the implemented interfaces
     * and finally all base class factories are copied.
     *
     * @param clazz The adaptable <code>Class</code> for which to build the
     *            adapter factory map by target class name.
     * @return The map of adapter factories by target class name. The map may be
     *         empty if there is no adapter factory for the adaptable
     *         <code>clazz</code>.
     */
    private Map<String, List<AdapterFactoryDescriptor>> createAdapterFactoryMap(final Class<?> clazz) {
        final Map<String, List<AdapterFactoryDescriptor>> afm = new HashMap<String, List<AdapterFactoryDescriptor>>();

        // AdapterFactories for this class
        AdapterFactoryDescriptorMap afdMap = null;
        synchronized (this.descriptors) {
            afdMap = this.descriptors.get(clazz.getName());
        }
        if (afdMap != null) {
            final List<AdapterFactoryDescriptor> afdSet;
            synchronized (afdMap) {
                afdSet = new ArrayList<AdapterFactoryDescriptor>(afdMap.values());
            }
            for (final AdapterFactoryDescriptor afd : afdSet) {
                final String[] adapters = afd.getAdapters();
                for (final String adapter : adapters) {
                    // to handle service ranking, we add to the end of the list or create a new list
                    List<AdapterFactoryDescriptor> factoryDescriptors = afm.get(adapter);
                    if (factoryDescriptors == null) {
                        factoryDescriptors = new ArrayList<AdapterFactoryDescriptor>();
                        afm.put(adapter, factoryDescriptors);
                    }
                    factoryDescriptors.add(afd);
                }
            }
        }

        // AdapterFactories for the interfaces
        final Class<?>[] interfaces = clazz.getInterfaces();
        for (final Class<?> iFace : interfaces) {
            copyAdapterFactories(afm, iFace);
        }

        // AdapterFactories for the super class
        final Class<?> superClazz = clazz.getSuperclass();
        if (superClazz != null) {
            copyAdapterFactories(afm, superClazz);
        }

        return afm;
    }

    /**
     * Copies all adapter factories for the given <code>clazz</code> from the
     * <code>cache</code> to the <code>dest</code> map except for those
     * factories whose target class already exists in the <code>dest</code>
     * map.
     *
     * @param dest The map of target class name to adapter factory into which
     *            additional factories are copied. Existing factories are not
     *            replaced.
     * @param clazz The adaptable class whose adapter factories are considered
     *            for adding into <code>dest</code>.
     */
    private void copyAdapterFactories(final Map<String, List<AdapterFactoryDescriptor>> dest, final Class<?> clazz) {

        // get the adapter factories for the adaptable clazz
        final Map<String, List<AdapterFactoryDescriptor>> scMap = getAdapterFactories(clazz);

        // for each target class copy the entry to dest and put it in the list or create the list
        for (Map.Entry<String, List<AdapterFactoryDescriptor>> entry : scMap.entrySet()) {

            List<AdapterFactoryDescriptor> factoryDescriptors = dest.get(entry.getKey());

            if (factoryDescriptors == null) {
                factoryDescriptors = new ArrayList<AdapterFactoryDescriptor>();
                dest.put(entry.getKey(), factoryDescriptors);
            }
            for (AdapterFactoryDescriptor descriptor : entry.getValue()) {
                factoryDescriptors.add(descriptor);
            }
        }
    }

    /**
     * The <code>AdapterFactoryDescriptorMap</code> is a sorted map of
     * {@link AdapterFactoryDescriptor} instances indexed (and ordered) by their
     * {@link ServiceReference}. This map is used to organize the
     * registered {@link org.apache.sling.api.adapter.AdapterFactory} services for
     * a given adaptable type.
     * <p>
     * Each entry in the map is a {@link AdapterFactoryDescriptor} thus enabling the
     * registration of multiple factories for the same (adaptable, adapter) type
     * tuple. Of course only the first entry (this is the reason for having a sorted
     * map) for such a given tuple is actually being used. If that first instance is
     * removed the eventual second instance may actually be used instead.
     *
     * <p>
     *   Copy of import org.apache.sling.adapter.internal.AdapterFactoryDescriptorMap,
     *   signature has changed in adapter impl 2.2.0.
     * </p>
     */
    static class AdapterFactoryDescriptorMap
            extends TreeMap<ServiceReference<AdapterFactory>, AdapterFactoryDescriptor> {

        private static final long serialVersionUID = 2L;
    }

    /**
     * The <code>AdapterFactoryDescriptor</code> is an entry in the
     * {@link AdapterFactoryDescriptorMap} conveying the list of adapter (target)
     * types and the respective {@link AdapterFactory}.
     * <p>
     *   Copy of import org.apache.sling.adapter.internal.AdapterFactoryDescriptor,
     *   signature has changed in adapter impl 2.2.0.
     * </p>
     */
    static class AdapterFactoryDescriptor {

        private volatile AdapterFactory factory;

        private final String[] adapters;

        private final ServiceReference<AdapterFactory> reference;

        private final ComponentContext context;

        private volatile ServiceRegistration<Adaption> adaption;

        public AdapterFactoryDescriptor(
                final ComponentContext context,
                final ServiceReference<AdapterFactory> reference,
                final String[] adapters) {
            this.reference = reference;
            this.context = context;
            this.adapters = adapters;
        }

        public AdapterFactory getFactory() {
            if (factory == null) {
                factory = context.locateService("AdapterFactory", reference);
            }
            return factory;
        }

        public String[] getAdapters() {
            return adapters;
        }

        public ServiceRegistration<Adaption> getAdaption() {
            return adaption;
        }

        public void setAdaption(ServiceRegistration<Adaption> adaption) {
            this.adaption = adaption;
        }
    }
}
