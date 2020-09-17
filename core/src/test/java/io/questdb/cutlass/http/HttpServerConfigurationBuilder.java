package io.questdb.cutlass.http;

import io.questdb.cutlass.http.processors.JsonQueryProcessorConfiguration;
import io.questdb.cutlass.http.processors.StaticContentProcessorConfiguration;
import io.questdb.network.DefaultIODispatcherConfiguration;
import io.questdb.network.IODispatcherConfiguration;
import io.questdb.network.NetworkFacade;
import io.questdb.network.NetworkFacadeImpl;
import io.questdb.std.FilesFacade;
import io.questdb.std.FilesFacadeImpl;
import io.questdb.std.Numbers;
import io.questdb.std.time.MillisecondClock;

public class HttpServerConfigurationBuilder {
    private NetworkFacade nf = NetworkFacadeImpl.INSTANCE;
    private String baseDir;
    private int sendBufferSize = 1024 * 1024;
    private boolean dumpTraffic;
    private boolean allowDeflateBeforeSend;
    private boolean serverKeepAlive;
    private String httpProtocolVersion = "HTTP/1.1 ";
    private int configuredMaxQueryResponseRowLimit;

    public HttpServerConfigurationBuilder withNetwork(NetworkFacade nf) {
        this.nf = nf;
        return this;
    }
    
    public HttpServerConfigurationBuilder withBaseDir(String baseDir) {
        this.baseDir = baseDir;
        return this;
    }

    public HttpServerConfigurationBuilder withSendBufferSize(int sendBufferSize) {
        this.sendBufferSize = sendBufferSize;
        return this;
    }

    public HttpServerConfigurationBuilder withDumpingTraffic(boolean dumpTraffic) {
        this.dumpTraffic = dumpTraffic;
        return this;
    }

    public HttpServerConfigurationBuilder withAllowDeflateBeforeSend(boolean allowDeflateBeforeSend) {
        this.allowDeflateBeforeSend = allowDeflateBeforeSend;
        return this;
    }

    public HttpServerConfigurationBuilder withServerKeepAlive(boolean serverKeepAlive) {
        this.serverKeepAlive = serverKeepAlive;
        return this;
    }

    public HttpServerConfigurationBuilder withHttpProtocolVersion(String httpProtocolVersion) {
        this.httpProtocolVersion = httpProtocolVersion;
        return this;
    }

    public HttpServerConfigurationBuilder withConfiguredMaxQueryResponseRowLimit(int configuredMaxQueryResponseRowLimit) {
        this.configuredMaxQueryResponseRowLimit = configuredMaxQueryResponseRowLimit;
        return this;
    }


    public DefaultHttpServerConfiguration build() {
        final IODispatcherConfiguration ioDispatcherConfiguration = new DefaultIODispatcherConfiguration() {
            @Override
            public NetworkFacade getNetworkFacade() {
                return nf;
            }
        };

        return new DefaultHttpServerConfiguration() {
            private final StaticContentProcessorConfiguration staticContentProcessorConfiguration = new StaticContentProcessorConfiguration() {
                @Override
                public FilesFacade getFilesFacade() {
                    return FilesFacadeImpl.INSTANCE;
                }

                @Override
                public CharSequence getIndexFileName() {
                    return null;
                }

                @Override
                public MimeTypesCache getMimeTypesCache() {
                    return mimeTypesCache;
                }

                @Override
                public CharSequence getPublicDirectory() {
                    return baseDir;
                }

                @Override
                public String getKeepAliveHeader() {
                    return null;
                }
            };

            private final JsonQueryProcessorConfiguration jsonQueryProcessorConfiguration = new JsonQueryProcessorConfiguration() {
                @Override
                public MillisecondClock getClock() {
                    return () -> 0;
                }

                @Override
                public int getConnectionCheckFrequency() {
                    return 1_000_000;
                }

                @Override
                public FilesFacade getFilesFacade() {
                    return FilesFacadeImpl.INSTANCE;
                }

                @Override
                public int getFloatScale() {
                    return 10;
                }

                @Override
                public int getDoubleScale() {
                    return Numbers.MAX_SCALE;
                }

                @Override
                public CharSequence getKeepAliveHeader() {
                    return "Keep-Alive: timeout=5, max=10000\r\n";
                }

                @Override
                public long getMaxQueryResponseRowLimit() {
                    return configuredMaxQueryResponseRowLimit;
                }
            };

            @Override
            public MillisecondClock getClock() {
                return () -> 0;
            }

            @Override
            public IODispatcherConfiguration getDispatcherConfiguration() {
                return ioDispatcherConfiguration;
            }

            @Override
            public StaticContentProcessorConfiguration getStaticContentProcessorConfiguration() {
                return staticContentProcessorConfiguration;
            }

            @Override
            public JsonQueryProcessorConfiguration getJsonQueryProcessorConfiguration() {
                return jsonQueryProcessorConfiguration;
            }

            @Override
            public int getSendBufferSize() {
                return sendBufferSize;
            }

            @Override
            public boolean getDumpNetworkTraffic() {
                return dumpTraffic;
            }

            @Override
            public boolean allowDeflateBeforeSend() {
                return allowDeflateBeforeSend;
            }

            @Override
            public boolean getServerKeepAlive() {
                return serverKeepAlive;
            }

            @Override
            public String getHttpVersion() {
                return httpProtocolVersion;
            }
        };
    }
}
