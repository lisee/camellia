package com.netease.nim.camellia.redis.resource;

import com.netease.nim.camellia.core.model.Resource;
import com.netease.nim.camellia.redis.exception.CamelliaRedisException;
import com.netease.nim.camellia.redis.proxy.*;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.List;

/**
 *
 * Created by caojiajun on 2019/11/8.
 */
public class RedisResourceUtil {

    public static Resource parseResourceByUrl(Resource resource) {
        try {
            if (resource == null) return null;
            String url = resource.getUrl();
            if (url == null) {
                throw new CamelliaRedisException("url is null");
            }
            if (url.startsWith(RedisType.Redis.getPrefix())) {
                String substring = url.substring(RedisType.Redis.getPrefix().length());

                if (!substring.contains("@")) {
                    throw new CamelliaRedisException("missing @");
                }

                int index = substring.lastIndexOf("@");
                String password = substring.substring(0, index);
                String split = substring.substring(index + 1, substring.length());
                String[] split2 = split.split(":");
                String host = split2[0];
                int port = Integer.parseInt(split2[1]);
                if (password.length() == 0) {
                    password = null;
                }
                RedisResource redisResource = new RedisResource(host, port, password);
                if (!redisResource.getUrl().equals(resource.getUrl())) {
                    throw new CamelliaRedisException("resource url not equals");
                }
                return redisResource;
            } else if (url.startsWith(RedisType.RedisSentinel.getPrefix())) {
                String substring = url.substring(RedisType.RedisSentinel.getPrefix().length());
                if (!substring.contains("@")) {
                    throw new CamelliaRedisException("missing @");
                }
                if (!substring.contains("/")) {
                    throw new CamelliaRedisException("missing /");
                }

                int index = substring.lastIndexOf("@");
                String password = substring.substring(0, index);
                String split = substring.substring(index + 1, substring.length());

                int index2 = split.indexOf("/");
                String hostPorts = split.substring(0, index2);
                String master = split.substring(index2 + 1, split.length());

                String[] split2 = hostPorts.split(",");
                List<RedisSentinelResource.Node> nodeList = new ArrayList<>();
                for (String node : split2) {
                    String[] split3 = node.split(":");
                    String host = split3[0];
                    int port = Integer.parseInt(split3[1]);
                    nodeList.add(new RedisSentinelResource.Node(host, port));
                }
                RedisSentinelResource redisSentinelResource = new RedisSentinelResource(master, nodeList, password);
                if (!redisSentinelResource.getUrl().equals(resource.getUrl())) {
                    throw new CamelliaRedisException("resource url not equals");
                }
                return redisSentinelResource;
            } else if (url.startsWith(RedisType.RedisCluster.getPrefix())) {
                String substring = url.substring(RedisType.RedisCluster.getPrefix().length());
                if (!substring.contains("@")) {
                    throw new CamelliaRedisException("missing @");
                }

                int index = substring.lastIndexOf("@");
                String password = substring.substring(0, index);
                String split = substring.substring(index + 1, substring.length());

                String[] split2 = split.split(",");
                List<RedisClusterResource.Node> nodeList = new ArrayList<>();
                for (String node : split2) {
                    String[] split1 = node.split(":");
                    String ip = split1[0];
                    int port = Integer.parseInt(split1[1]);
                    nodeList.add(new RedisClusterResource.Node(ip, port));
                }
                if (password.length() == 0) {
                    password = null;
                }
                RedisClusterResource redisClusterResource = new RedisClusterResource(nodeList, password);
                if (!redisClusterResource.getUrl().equals(resource.getUrl())) {
                    throw new CamelliaRedisException("resource url not equals");
                }
                return redisClusterResource;
            } else if (url.startsWith(RedisType.RedisProxy.getPrefix())) {
                String substring = url.substring(RedisType.RedisProxy.getPrefix().length());
                long id = Long.parseLong(substring);
                RedisProxyJedisPool pool = RedisProxyJedisPoolContext.get(id);
                if (pool == null) {
                    throw new CamelliaRedisException("not found RedisProxyJedisPool with id = " + id);
                }
                RedisProxyResource redisProxyResource = new RedisProxyResource(pool);
                if (!redisProxyResource.getUrl().equals(resource.getUrl())) {
                    throw new CamelliaRedisException("resource url not equals");
                }
                return redisProxyResource;
            } else if (url.startsWith(RedisType.CamelliaRedisProxy.getPrefix())) {
                String substring = url.substring(RedisType.CamelliaRedisProxy.getPrefix().length());
                if (!substring.contains("@")) {
                    throw new CamelliaRedisException("missing @");
                }
                int index = substring.lastIndexOf("@");
                String password = substring.substring(0, index);
                String proxyName = substring.substring(index + 1, substring.length());
                if (password.length() == 0) {
                    password = null;
                }
                CamelliaRedisProxyResource camelliaRedisProxyResource = new CamelliaRedisProxyResource(password, proxyName);
                if (!camelliaRedisProxyResource.getUrl().equals(resource.getUrl())) {
                    throw new CamelliaRedisException("resource url not equals");
                }
                CamelliaRedisProxyFactory factory = CamelliaRedisProxyContext.getFactory();
                if (factory == null) {
                    throw new CamelliaRedisException("no CamelliaRedisProxyFactory register to CamelliaRedisProxyContext");
                }
                JedisPool jedisPool = factory.initOrGet(camelliaRedisProxyResource);
                if (jedisPool == null) {
                    throw new CamelliaRedisException("CamelliaRedisProxyFactory initOrGet JedisPool fail");
                }
                return camelliaRedisProxyResource;
            }
            throw new CamelliaRedisException("not redis resource");
        } catch (CamelliaRedisException e) {
            throw e;
        } catch (Exception e) {
            throw new CamelliaRedisException(e);
        }
    }
}
