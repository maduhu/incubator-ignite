/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.plugin;

import org.apache.ignite.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.plugin.*;
import org.jetbrains.annotations.*;

import java.lang.reflect.*;
import java.util.*;

/**
 *
 */
public class IgnitePluginProcessor extends GridProcessorAdapter {
    /** */
    private final Map<String, PluginProvider> plugins = new LinkedHashMap<>();

    /** */
    private final Map<PluginProvider, GridPluginContext> pluginCtxMap = new IdentityHashMap<>();

    /** */
    private volatile Map<Class<?>, Object[]> extensions;

    /**
     *
     * @param ctx Kernal context.
     * @param cfg Ignite configuration.
     */
    @SuppressWarnings("TypeMayBeWeakened")
    public IgnitePluginProcessor(GridKernalContext ctx, IgniteConfiguration cfg) {
        super(ctx);

        ExtensionRegistryImpl registry = new ExtensionRegistryImpl();

        if (cfg.getPluginConfigurations() != null) {
            for (PluginConfiguration pluginCfg : cfg.getPluginConfigurations()) {
                GridPluginContext pluginCtx = new GridPluginContext(ctx, pluginCfg, cfg);

                PluginProvider provider;

                try {
                    if (pluginCfg.providerClass() == null)
                        throw new IgniteException("Provider class is null.");

                    try {
                        Constructor<? extends PluginProvider> ctr =
                            pluginCfg.providerClass().getConstructor(PluginContext.class);

                        provider = ctr.newInstance(pluginCtx);
                    }
                    catch (NoSuchMethodException ignore) {
                        try {
                            Constructor<? extends PluginProvider> ctr =
                                pluginCfg.providerClass().getConstructor(pluginCfg.getClass());

                            provider = ctr.newInstance(pluginCfg);
                        }
                        catch (NoSuchMethodException ignored) {
                            provider = pluginCfg.providerClass().newInstance();
                        }
                    }
                }
                catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                    throw new IgniteException("Failed to create plugin provider instance.", e);
                }

                if (F.isEmpty(provider.name()))
                    throw new IgniteException("Plugin name can not be empty.");

                if (provider.plugin() == null)
                    throw new IgniteException("Plugin is null.");

                if (plugins.containsKey(provider.name()))
                    throw new IgniteException("Duplicated plugin name: " + provider.name());

                plugins.put(provider.name(), provider);

                pluginCtxMap.put(provider, pluginCtx);

                provider.initExtensions(pluginCtx, registry);
            }
        }

        extensions = registry.createExtensionMap();
    }

    /**
     * @param extensionItf Extension interface class.
     * @return Returns implementation for provided extension from all plugins.
     */
    @Nullable public <T extends Extension> T[] extensions(Class<T> extensionItf) {
        Map<Class<?>, Object[]> extensions = this.extensions;

        return (T[])extensions.get(extensionItf);
    }

    /**
     * @param name Plugin name.
     * @return Plugin provider.
     */
    @SuppressWarnings("unchecked")
    @Nullable public <T extends PluginProvider> T pluginProvider(String name) {
        return (T)plugins.get(name);
    }

    /**
     * @return All plugin providers.
     */
    public Collection<PluginProvider> allProviders() {
        return plugins.values();
    }

    /**
     * @param provider Plugin context.
     * @return Plugin context.
     */
    @SuppressWarnings("unchecked")
    public <T extends PluginContext> T pluginContextForProvider(PluginProvider provider) {
        return (T)pluginCtxMap.get(provider);
    }

    /**
     * @param cls Component class.
     * @param <T> Component type.
     * @return Component class instance or {@code null} if no one plugin override this component.
     */
    public <T> T createComponent(Class<T> cls) {
        for (PluginProvider plugin : plugins.values()) {
            PluginContext ctx = pluginContextForProvider(plugin);

            T comp = (T)plugin.createComponent(ctx, cls);

            if (comp != null)
                return comp;
        }

        return null;
    }

    /** {@inheritDoc} */
    @Override public void start() throws IgniteCheckedException {
        ackPluginsInfo();
    }

    /** {@inheritDoc} */
    @Nullable @Override public DiscoveryDataExchangeType discoveryDataType() {
        return DiscoveryDataExchangeType.PLUGIN;
    }

    /** {@inheritDoc} */
    @Nullable @Override public Object collectDiscoveryData(UUID nodeId) {
        Map<String, Object> discData = null;

        for (Map.Entry<String, PluginProvider> e : plugins.entrySet()) {
            Object data = e.getValue().provideDiscoveryData(nodeId);

            if (data != null) {
                if (discData == null)
                    discData = new HashMap<>();

                discData.put(e.getKey(), data);
            }
        }

        return discData;
    }

    /** {@inheritDoc} */
    @Override public void onDiscoveryDataReceived(UUID nodeId, UUID rmtNodeId, Object data) {
        Map<String, Object> discData = (Map<String, Object>)data;

        if (discData != null) {
            for (Map.Entry<String, Object> e : discData.entrySet()) {
                PluginProvider provider = plugins.get(e.getKey());

                if (provider != null)
                    provider.receiveDiscoveryData(nodeId, e.getValue());
                else
                    U.warn(log, "Received discovery data for unknown plugin: " + e.getKey());
            }
        }
    }

    /**
     * Print plugin information.
     */
    private void ackPluginsInfo() {
        U.quietAndInfo(log, "Configured plugins:");

        if (plugins.isEmpty()) {
            U.quietAndInfo(log, "  ^-- None");
            U.quietAndInfo(log, "");
        }
        else {
            for (PluginProvider plugin : plugins.values()) {
                U.quietAndInfo(log, "  ^-- " + plugin.name() + " " + plugin.version());
                U.quietAndInfo(log, "  ^-- " + plugin.copyright());
                U.quietAndInfo(log, "");
            }
        }
    }

    /**
     *
     */
    private static class ExtensionRegistryImpl implements ExtensionRegistry {
        /** */
        private final Map<Class<?>, List<Object>> extensionsCollector = new HashMap<>();

        /** {@inheritDoc} */
        @Override public <T extends Extension> void registerExtension(Class<T> extensionItf, T extensionImpl) {
            List<Object> list = extensionsCollector.get(extensionItf);

            if (list == null) {
                list = new ArrayList<>();

                extensionsCollector.put(extensionItf, list);
            }

            list.add(extensionImpl);
        }

        /**
         * @return Map extension interface to array of implementation.
         */
        public Map<Class<?>, Object[]> createExtensionMap() {
            Map<Class<?>, Object[]> extensions = new HashMap<>(extensionsCollector.size() * 2, 0.5f);

            for (Map.Entry<Class<?>, List<Object>> entry : extensionsCollector.entrySet()) {
                Class<?> extensionItf = entry.getKey();

                List<Object> implementations = entry.getValue();

                Object[] implArr = (Object[])Array.newInstance(extensionItf, implementations.size());

                implArr = implementations.toArray(implArr);

                extensions.put(extensionItf, implArr);
            }

            return extensions;
        }
    }
}
