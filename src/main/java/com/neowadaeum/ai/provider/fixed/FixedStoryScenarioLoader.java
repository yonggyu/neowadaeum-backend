package com.neowadaeum.ai.provider.fixed;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import tools.jackson.core.JacksonException;

/**
 * 클래스패스에서 시나리오 파일을 읽는다 (S-3).
 *
 * <p>읽기에 실패하면 <b>그 자리에서 실패시킨다.</b> 시나리오가 없는 {@code FixedStoryProvider} 는
 * 조용히 아무것도 못 하는 Provider 가 되고, 그때의 증상은 "AI 가 이상하다"로 나타나 원인을 찾는 데
 * 시간이 든다. 부팅에서 끊는 편이 싸다.
 */
public class FixedStoryScenarioLoader {

	static final String DEFAULT_LOCATION_PATTERN = "classpath*:fixed-story/*.json";

	private final ObjectMapper objectMapper;
	private final ResourcePatternResolver resourceResolver;
	private final String locationPattern;

	public FixedStoryScenarioLoader(ObjectMapper objectMapper) {
		this(objectMapper, new PathMatchingResourcePatternResolver(), DEFAULT_LOCATION_PATTERN);
	}

	FixedStoryScenarioLoader(ObjectMapper objectMapper, ResourcePatternResolver resourceResolver,
			String locationPattern) {
		this.objectMapper = objectMapper;
		this.resourceResolver = resourceResolver;
		this.locationPattern = locationPattern;
	}

	public List<FixedStoryScenario> load() {
		Resource[] resources;
		try {
			resources = resourceResolver.getResources(locationPattern);
		}
		catch (IOException ex) {
			throw new IllegalStateException("failed to scan fixed-story scenarios at " + locationPattern, ex);
		}

		List<FixedStoryScenario> scenarios = new ArrayList<>();
		for (Resource resource : resources) {
			scenarios.add(read(resource));
		}

		if (scenarios.isEmpty()) {
			throw new IllegalStateException("no fixed-story scenario found at " + locationPattern);
		}
		return List.copyOf(scenarios);
	}

	private FixedStoryScenario read(Resource resource) {
		try (InputStream in = resource.getInputStream()) {
			return objectMapper.readValue(in, FixedStoryScenario.class);
		}
		catch (IOException | JacksonException ex) {
			// 파일명만 남긴다. 본문에는 작품 텍스트가 들어 있고, 예외 메시지는 로그로 흐른다 (S-3).
			throw new IllegalStateException("invalid fixed-story scenario: " + resource.getFilename(), ex);
		}
	}
}
