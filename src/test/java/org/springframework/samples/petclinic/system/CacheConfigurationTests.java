package org.springframework.samples.petclinic.system;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.cache.JCacheManagerCustomizer;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;

class CacheConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(CacheAutoConfiguration.class, RedisAutoConfiguration.class))
			.withUserConfiguration(CacheConfiguration.class);

	@Test
	void shouldKeepJCacheCustomizationForDefaultBuilds() {
		this.contextRunner.withBean(CacheManager.class, () -> new ConcurrentMapCacheManager("vets"))
				.run(context -> assertThat(context).hasSingleBean(JCacheManagerCustomizer.class));
	}

	@Test
	void shouldUseRedisCachingWithAllThreeSentinels() {
		this.contextRunner
				.withPropertyValues("spring.cache.type=redis", "spring.cache.cache-names=vets",
						"spring.redis.sentinel.master=mymaster",
						"spring.redis.sentinel.nodes=sentinel-0:26379,sentinel-1:26379,sentinel-2:26379")
				.run(context -> {
					assertThat(context).hasSingleBean(RedisCacheManager.class);
					assertThat(context).doesNotHaveBean(JCacheManagerCustomizer.class);
					assertThat(context.getBean(RedisCacheManager.class).getCache("vets")).isNotNull();
					LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
					assertThat(factory.getSentinelConfiguration().getMaster().getName()).isEqualTo("mymaster");
					assertThat(factory.getSentinelConfiguration().getSentinels()).extracting(RedisNode::getHost)
							.containsExactlyInAnyOrder("sentinel-0", "sentinel-1", "sentinel-2");
				});
	}

}
