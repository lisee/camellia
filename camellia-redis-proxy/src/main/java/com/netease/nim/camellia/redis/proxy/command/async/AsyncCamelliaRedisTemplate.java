package com.netease.nim.camellia.redis.proxy.command.async;

import com.netease.nim.camellia.core.api.*;
import com.netease.nim.camellia.core.client.env.Monitor;
import com.netease.nim.camellia.core.client.env.ProxyEnv;
import com.netease.nim.camellia.core.model.Resource;
import com.netease.nim.camellia.core.model.ResourceTable;
import com.netease.nim.camellia.core.util.CamelliaThreadFactory;
import com.netease.nim.camellia.core.util.ReadableResourceTableUtil;
import com.netease.nim.camellia.core.util.ResourceChooser;
import com.netease.nim.camellia.core.util.ResourceUtil;
import com.netease.nim.camellia.redis.exception.CamelliaRedisException;
import com.netease.nim.camellia.redis.proxy.command.Command;
import com.netease.nim.camellia.redis.proxy.enums.RedisCommand;
import com.netease.nim.camellia.redis.proxy.reply.*;
import com.netease.nim.camellia.redis.proxy.util.RedisKey;
import com.netease.nim.camellia.redis.proxy.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *
 * Created by caojiajun on 2019/12/19.
 */
public class AsyncCamelliaRedisTemplate implements IAsyncCamelliaRedisTemplate {

    private static final Logger logger = LoggerFactory.getLogger(AsyncCamelliaRedisTemplate.class);

    private static final long defaultBid = -1;
    private static final String defaultBgroup = "local";
    private static final long defaultCheckIntervalMillis = 5000;
    private static final boolean defaultMonitorEnable = false;

    private AsyncNettyClientFactory factory;

    private long bid;
    private String bgroup;
    private AsyncCamelliaRedisEnv env;
    private ResourceChooser resourceChooser;

    public AsyncCamelliaRedisTemplate(ResourceTable resourceTable) {
        this(AsyncCamelliaRedisEnv.defaultRedisEnv(), resourceTable);
    }

    public AsyncCamelliaRedisTemplate(AsyncCamelliaRedisEnv env, ResourceTable resourceTable) {
        this(env, new LocalCamelliaApi(resourceTable), defaultBid, defaultBgroup, defaultMonitorEnable, defaultCheckIntervalMillis);
    }

    public AsyncCamelliaRedisTemplate(AsyncCamelliaRedisEnv env, CamelliaApi service, long bid, String bgroup,
                                        boolean monitorEnable, long checkIntervalMillis) {
        this.env = env;
        this.bid = bid;
        this.bgroup = bgroup;
        this.factory = env.getClientFactory();
        CamelliaApiResponse response = service.getResourceTable(bid, bgroup, null);
        String md5 = response.getMd5();
        if (response.getResourceTable() == null) {
            throw new CamelliaRedisException("resourceTable is null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("AsyncCamelliaRedisTemplate init success, bid = {}, bgroup = {}, md5 = {}, resourceTable = {}", bid, bgroup, md5,
                    ReadableResourceTableUtil.readableResourceTable(response.getResourceTable()));
        }
        this.init(response.getResourceTable());

        if (bid > 0) {
            ReloadTask reloadTask = new ReloadTask(this, service, bid, bgroup, md5);
            Executors.newSingleThreadScheduledExecutor(new CamelliaThreadFactory(ReloadTask.class))
                    .scheduleAtFixedRate(reloadTask, checkIntervalMillis, checkIntervalMillis, TimeUnit.MILLISECONDS);
            if (monitorEnable) {
                Monitor monitor = new RemoteMonitor(bid, bgroup, service);
                ProxyEnv proxyEnv = new ProxyEnv.Builder(env.getProxyEnv()).monitor(monitor).build();
                this.env = new AsyncCamelliaRedisEnv.Builder(env).proxyEnv(proxyEnv).build();
            }
        }
    }

    public List<CompletableFuture<Reply>> sendCommand(List<Command> commands) {
        List<CompletableFuture<Reply>> futureList = new ArrayList<>(commands.size());

        CommandFlusher commandFlusher = new CommandFlusher();
        for (Command command : commands) {
            RedisCommand redisCommand = RedisCommand.getSupportRedisCommand(command);
            if (redisCommand == null || !redisCommand.isSupport()) {
                CompletableFuture<Reply> future = new CompletableFuture<>();
                future.complete(ErrorReply.NOT_SUPPORT);
                futureList.add(future);
                continue;
            }

            if (command.getName().equalsIgnoreCase(RedisCommand.PING.name())) {
                CompletableFuture<Reply> completableFuture = new CompletableFuture<>();
                completableFuture.complete(StatusReply.PONG);
                futureList.add(completableFuture);
                continue;
            }

            //特殊处理多key的命令
            if (resourceChooser.getType() == ResourceTable.Type.SHADING) {
                if (command.getName().equalsIgnoreCase(RedisCommand.MGET.name())) {
                    if (command.getObjects().length > 2) {
                        CompletableFuture<Reply> future = mget(command, commandFlusher);
                        futureList.add(future);
                        continue;
                    }
                } else if (command.getName().equalsIgnoreCase(RedisCommand.DEL.name())) {
                    if (command.getObjects().length > 2) {
                        CompletableFuture<Reply> future = del(command, commandFlusher);
                        futureList.add(future);
                        continue;
                    }
                } else if (command.getName().equalsIgnoreCase(RedisCommand.MSET.name())) {
                    if (command.getObjects().length > 3) {
                        CompletableFuture<Reply> future = mset(command, commandFlusher);
                        futureList.add(future);
                        continue;
                    }
                } else if (command.getName().equalsIgnoreCase(RedisCommand.EXISTS.name())) {
                    if (command.getObjects().length > 2) {
                        CompletableFuture<Reply> future = exists(command, commandFlusher);
                        futureList.add(future);
                        continue;
                    }
                }
            }

            RedisCommand.Type type = redisCommand.getType();
            if (type == RedisCommand.Type.READ) {
                Resource resource = getReadResource(command);
                AsyncClient client = factory.get(resource.getUrl());
                CompletableFuture<Reply> future = commandFlusher.sendCommand(client, command);
                incrRead(resource, command);
                futureList.add(future);
            } else if (type == RedisCommand.Type.WRITE) {
                List<Resource> writeResources = getWriteResources(command);
                for (int i=0; i<writeResources.size(); i++) {
                    Resource resource = writeResources.get(i);
                    AsyncClient client = factory.get(resource.getUrl());
                    CompletableFuture<Reply> future = commandFlusher.sendCommand(client, command);
                    incrWrite(resource, command);
                    if (i == 0) {
                        futureList.add(future);
                    }
                }
            } else {
                throw new CamelliaRedisException("not support RedisCommand.Type");
            }
        }
        commandFlusher.flush();
        return futureList;
    }

    private CompletableFuture<Reply> mset(Command command, CommandFlusher commandFlusher) {
        byte[][] args = command.getObjects();

        if ((args.length - 1) % 2 != 0) {
            CompletableFuture<Reply> future = new CompletableFuture<>();
            future.complete(new ErrorReply("wrong number of arguments for 'mset' command"));
            return future;
        }
        Set<String> firstWriteResourceUrls = new HashSet<>();
        Map<String, List<byte[]>> map = new HashMap<>();
        for (int i=1; i<args.length; i++, i++) {
            byte[] key = args[i];
            byte[] value = args[i+1];
            List<Resource> resources = getWriteResources(key);
            for (int j=0; j<resources.size(); j++) {
                Resource resource = resources.get(j);
                List<byte[]> list = map.get(resource.getUrl());
                if (list == null) {
                    list = new ArrayList<>();
                    list.add(args[0]);
                    map.put(resource.getUrl(), list);
                }
                list.add(key);
                list.add(value);
                if (j == 0) {
                    firstWriteResourceUrls.add(resource.getUrl());
                }
            }
        }
        List<CompletableFuture<Reply>> futures = new ArrayList<>();
        for (Map.Entry<String, List<byte[]>> entry : map.entrySet()) {
            String url = entry.getKey();
            List<byte[]> list = entry.getValue();
            AsyncClient client = factory.get(url);
            CompletableFuture<Reply> future = commandFlusher.sendCommand(client, new Command(list.toArray(new byte[0][0])));
            incrWrite(url, command);
            if (firstWriteResourceUrls.contains(url)) {
                futures.add(future);
            }
        }
        if (futures.size() == 1) {
            return futures.get(0);
        }
        CompletableFuture<Reply> future = new CompletableFuture<>();
        AsyncUtils.allOf(futures).thenAccept(replies -> future.complete(Utils.mergeStatusReply(replies)));
        return future;
    }

    private CompletableFuture<Reply> mget(Command command, CommandFlusher commandFlusher) {
        List<RedisKey> redisKeys = new ArrayList<>();
        Map<String, List<RedisKey>> map = new HashMap<>();
        byte[][] args = command.getObjects();
        for (int i=1; i<args.length; i++) {
            byte[] key = args[i];
            RedisKey redisKey = new RedisKey(key);
            redisKeys.add(redisKey);
            Resource resource = getReadResource(key);
            List<RedisKey> list = map.computeIfAbsent(resource.getUrl(), k -> new ArrayList<>());
            list.add(redisKey);
        }
        List<String> urlList = new ArrayList<>();
        List<CompletableFuture<Reply>> futures = new ArrayList<>();
        for (Map.Entry<String, List<RedisKey>> entry : map.entrySet()) {
            String url = entry.getKey();
            AsyncClient client = factory.get(url);
            List<RedisKey> list = entry.getValue();
            List<byte[]> subCommandArgs = new ArrayList<>(list.size() + 1);
            subCommandArgs.add(args[0]);
            for (RedisKey redisKey : list) {
                subCommandArgs.add(redisKey.getKey());
            }
            urlList.add(url);
            CompletableFuture<Reply> future = commandFlusher.sendCommand(client, new Command(subCommandArgs.toArray(new byte[0][0])));
            incrRead(url, command);
            futures.add(future);
        }
        if (futures.size() == 1) {
            return futures.get(0);
        }
        CompletableFuture<Reply> future = new CompletableFuture<>();
        AsyncUtils.allOf(futures).thenAccept(replies -> {
            Map<RedisKey, Reply> replyMap = new HashMap<>();
            for (int i=0; i<urlList.size(); i++) {
                String url = urlList.get(i);
                List<RedisKey> keyList = map.get(url);
                Reply reply = replies.get(i);
                if (reply instanceof MultiBulkReply) {
                    MultiBulkReply multiBulkReply = (MultiBulkReply) reply;
                    Reply[] subReplies = multiBulkReply.getReplies();
                    for (int j = 0; j < subReplies.length; j++) {
                        Reply subReply = subReplies[j];
                        RedisKey redisKey = keyList.get(j);
                        replyMap.put(redisKey, subReply);
                    }
                } else if (reply instanceof ErrorReply) {
                    future.complete(reply);
                    return;
                } else {
                    future.complete(ErrorReply.NOT_AVAILABLE);
                    return;
                }
            }
            Reply[] retReplies = new Reply[redisKeys.size()];
            for (int i=0; i<redisKeys.size(); i++) {
                RedisKey redisKey = redisKeys.get(i);
                retReplies[i] = replyMap.get(redisKey);
            }
            future.complete(new MultiBulkReply(retReplies));
        });
        return future;
    }

    private CompletableFuture<Reply> del(Command command, CommandFlusher commandFlusher) {
        ResourceChooser resourceChooser = this.resourceChooser;//避免过程中resourceChooser被替换
        ResourceTable.Type type = resourceChooser.getType();
        if (type == ResourceTable.Type.SIMPLE) {//如果不是分片，则可以直接转发
            CompletableFuture<Reply> completableFuture = null;
            List<Resource> resources = resourceChooser.getWriteResources(new byte[0]);
            for (int i=0; i<resources.size(); i++) {
                Resource resource = resources.get(i);
                AsyncClient client = factory.get(resource.getUrl());
                CompletableFuture<Reply> future = commandFlusher.sendCommand(client, command);
                if (i == 0) {
                    completableFuture = future;
                }
            }
            if (completableFuture == null) {
                //走不到这里
                completableFuture = new CompletableFuture<>();
                completableFuture.complete(ErrorReply.NOT_AVAILABLE);
            }
            return completableFuture;
        } else if (type == ResourceTable.Type.SHADING) {
            byte[][] args = command.getObjects();
            List<CompletableFuture<Reply>> futures = new ArrayList<>();
            //拆成N个命令进行投递，不做聚合，因为如果分片逻辑下，可能某个resource同时为双写的第1个地址和第2个地址
            for (int i = 1; i < args.length; i++) {
                byte[] key = args[i];
                List<Resource> resources = getWriteResources(key);
                for (int j = 0; j < resources.size(); j++) {
                    Resource resource = resources.get(j);
                    AsyncClient client = factory.get(resource.getUrl());
                    byte[][] subCommand = new byte[][]{args[0], key};
                    CompletableFuture<Reply> future = commandFlusher.sendCommand(client, new Command(subCommand));
                    incrWrite(resource, command);
                    if (j == 0) {
                        futures.add(future);
                    }
                }
            }
            if (futures.size() == 1) {
                return futures.get(0);
            }
            CompletableFuture<Reply> future = new CompletableFuture<>();
            AsyncUtils.allOf(futures).thenAccept(replies -> future.complete(Utils.mergeIntegerReply(replies)));
            return future;
        } else {
            CompletableFuture<Reply> completableFuture = new CompletableFuture<>();
            completableFuture.complete(ErrorReply.NOT_SUPPORT);
            return completableFuture;
        }
    }

    private CompletableFuture<Reply> exists(Command command, CommandFlusher commandFlusher) {
        Map<String, List<byte[]>> map = new HashMap<>();
        byte[][] args = command.getObjects();
        for (int i=1; i<args.length; i++) {
            byte[] key = args[i];
            Resource resource = getReadResource(key);
            List<byte[]> list = map.get(resource.getUrl());
            if (list == null) {
                list = new ArrayList<>();
                list.add(args[0]);
                map.put(resource.getUrl(), list);
            }
            list.add(key);
        }
        List<CompletableFuture<Reply>> futures = new ArrayList<>();
        for (Map.Entry<String, List<byte[]>> entry : map.entrySet()) {
            String url = entry.getKey();
            List<byte[]> list = entry.getValue();
            AsyncClient client = factory.get(url);

            Command subCommand = new Command(list.toArray(new byte[0][0]));
            CompletableFuture<Reply> future = commandFlusher.sendCommand(client, subCommand);
            incrRead(url, command);
            futures.add(future);
        }
        if (futures.size() == 1) {
            return futures.get(0);
        }
        CompletableFuture<Reply> future = new CompletableFuture<>();
        AsyncUtils.allOf(futures).thenAccept(replies -> future.complete(Utils.mergeIntegerReply(replies)));
        return future;
    }

    private void init(ResourceTable resourceTable) {
        Set<Resource> allResources = ResourceUtil.getAllResources(resourceTable);
        for (Resource resource : allResources) {
            //初始化一下
            factory.get(resource.getUrl());
        }
        this.resourceChooser = new ResourceChooser(resourceTable, env.getProxyEnv());
    }

    private static class ReloadTask implements Runnable {

        private AtomicBoolean running = new AtomicBoolean(false);

        private AsyncCamelliaRedisTemplate template;
        private CamelliaApi service;
        private long bid;
        private String bgroup;
        private String md5;

        ReloadTask(AsyncCamelliaRedisTemplate template, CamelliaApi service, long bid, String bgroup, String md5) {
            this.template = template;
            this.service = service;
            this.bid = bid;
            this.bgroup = bgroup;
            this.md5 = md5;
        }

        @Override
        public void run() {
            if (running.compareAndSet(false, true)) {
                try {
                    CamelliaApiResponse response = service.getResourceTable(bid, bgroup, md5);
                    if (response.getCode() == CamelliaApiCode.NOT_MODIFY.getCode()) {
                        if (logger.isTraceEnabled()) {
                            logger.trace("not modify, bid = {}, bgroup = {}, md5 = {}", bid, bgroup, md5);
                        }
                        return;
                    }
                    template.init(response.getResourceTable());
                    this.md5 = response.getMd5();
                    if (logger.isInfoEnabled()) {
                        logger.info("reload success, bid = {}, bgroup = {}, md5 = {}, resourceTable = {}", bid, bgroup, md5,
                                ReadableResourceTableUtil.readableResourceTable(response.getResourceTable()));
                    }
                } catch (Exception e) {
                    logger.error("reload error, bid = {}, bgroup = {}, md5 = {}", bid, bgroup, md5);
                } finally {
                    running.set(false);
                }
            } else {
                logger.warn("ReloadTask is running, skip run");
            }
        }
    }

    private Resource getReadResource(byte[] key) {
        return resourceChooser.getReadResources(key).get(0);
    }

    private List<Resource> getWriteResources(byte[] key) {
        return resourceChooser.getWriteResources(key);
    }

    private Resource getReadResource(Command command) {
        byte[] key = command.getObjects()[1];
        return resourceChooser.getReadResources(key).get(0);
    }

    private List<Resource> getWriteResources(Command command) {
        byte[] key = command.getObjects()[1];
        return resourceChooser.getWriteResources(key);
    }

    private static final String className = AsyncCamelliaRedisTemplate.class.getSimpleName();
    private void incrRead(String url, Command command) {
        if (env.getProxyEnv().getMonitor() != null) {
            env.getProxyEnv().getMonitor().incrRead(url, className, command.getName());
        }
        if (logger.isDebugEnabled()) {
            logger.debug("read command = {}, bid = {}, bgroup = {}, resource = {}", command.getName(), bid, bgroup, url);
        }
    }
    private void incrRead(Resource resource, Command command) {
        if (env.getProxyEnv().getMonitor() != null) {
            env.getProxyEnv().getMonitor().incrRead(resource.getUrl(), className, command.getName());
        }
        if (logger.isDebugEnabled()) {
            logger.debug("read command = {}, bid = {}, bgroup = {}, resource = {}", command.getName(), bid, bgroup, resource.getUrl());
        }
    }

    private void incrWrite(String url, Command command) {
        if (env.getProxyEnv().getMonitor() != null) {
            env.getProxyEnv().getMonitor().incrWrite(url, className, command.getName());
        }
        if (logger.isDebugEnabled()) {
            logger.debug("write command = {}, bid = {}, bgroup = {}, resource = {}", command.getName(), bid, bgroup, url);
        }
    }
    private void incrWrite(Resource resource, Command command) {
        if (env.getProxyEnv().getMonitor() != null) {
            env.getProxyEnv().getMonitor().incrWrite(resource.getUrl(), className, command.getName());
        }
        if (logger.isDebugEnabled()) {
            logger.debug("write command = {}, bid = {}, bgroup = {}, resource = {}", command.getName(), bid, bgroup, resource.getUrl());
        }
    }
}
