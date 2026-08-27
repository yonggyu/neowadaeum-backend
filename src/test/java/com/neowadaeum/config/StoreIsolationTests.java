package com.neowadaeum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neowadaeum.ContainerTestBase;
import jakarta.persistence.EntityManagerFactory;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * <b>#20 의 핵심 완료 조건</b> — 크로스 스키마 조인이 <b>구조적으로</b> 불가능한가 (§5.3, B-05-1).
 *
 * <p>이 확인은 지금까지 <b>성립할 수 없었다.</b> {@code PlayJpaConfiguration} 이 자기 주석에 그
 * 사실을 적어 뒀다 — <i>"비교 대상 엔티티가 없어 지금은 성립하지 않는다"</i>. 엔티티를 가진
 * 스토어가 {@code play} 하나뿐이었으므로, EMF 가 하나든 넷이든 <b>결과가 같았다.</b>
 *
 * <p>B-11 이 {@code promptlog} 에 {@code ai_call_log} 를 만들면서 비교 대상이 실재하게 됐다.
 * 여기서 보는 것은 <b>선언이 아니라 동작</b>이다 — 애노테이션이 붙어 있다는 확인이 아니라,
 * 다른 스토어의 엔티티를 실제로 물었을 때 거부되는가.
 *
 * <p><b>왜 FK 검증만으로 부족한가.</b> {@code StoreSeparationTests} 가 스키마 간 FK 가 없음을
 * 확인한다. 그러나 <b>JPQL 조인은 FK 를 요구하지 않는다</b> — 한 EMF 가 두 스토어의 엔티티를
 * 알기만 하면 조인이 성립하고, FK 검사는 그 경로를 전혀 보지 못한다. §5.3 분리가 지키려는 것이
 * 바로 그 경로다.
 */
class StoreIsolationTests extends ContainerTestBase {

	@Autowired
	@Qualifier("playEntityManagerFactory")
	private EntityManagerFactory playEntityManagerFactory;

	@Autowired
	@Qualifier("promptLogEntityManagerFactory")
	private EntityManagerFactory promptLogEntityManagerFactory;

	@Autowired
	@Qualifier("identityEntityManagerFactory")
	private EntityManagerFactory identityEntityManagerFactory;

	@Autowired
	@Qualifier("catalogEntityManagerFactory")
	private EntityManagerFactory catalogEntityManagerFactory;

	/**
	 * <b>EMF 는 자기 스토어의 엔티티만 안다.</b>
	 *
	 * <p>{@code packagesToScan} 이 넓어지면 여기서 먼저 드러난다 — 조인이 가능해진 뒤가 아니라.
	 */
	@Test
	void S5_3_each_entity_manager_factory_knows_only_its_own_store() {
		Set<String> playEntities = entityNames(this.playEntityManagerFactory);
		Set<String> promptLogEntities = entityNames(this.promptLogEntityManagerFactory);
		Set<String> identityEntities = entityNames(this.identityEntityManagerFactory);
		Set<String> catalogEntities = entityNames(this.catalogEntityManagerFactory);

		assertThat(playEntities).contains("PlaySession", "Turn", "GameStateSnapshot");
		assertThat(promptLogEntities).containsExactlyInAnyOrder("AiCallLog", "AdminAuditLog", "AccessAuditLog");
		assertThat(identityEntities)
				.containsExactlyInAnyOrder("User", "OauthIdentity", "ConsentLog", "AiNoticeImpression",
						"AdminTotp");


		assertThat(playEntities)
				.as("한 EMF 가 두 스토어의 엔티티를 알면 JPQL 한 줄로 크로스 스키마 조인이 된다")
				.doesNotContainAnyElementsOf(promptLogEntities)
				.doesNotContainAnyElementsOf(identityEntities);
		assertThat(catalogEntities)
				.containsExactlyInAnyOrder("Genre", "StoryGenre", "AuthorProfile", "EndingStat", "ServiceConfig");

		assertThat(promptLogEntities).doesNotContainAnyElementsOf(identityEntities);
		assertThat(catalogEntities)
				.doesNotContainAnyElementsOf(playEntities)
				.doesNotContainAnyElementsOf(identityEntities);
	}

	/**
	 * <b>다른 스토어의 엔티티를 참조하는 JPQL 은 매핑 단계에서 거부된다</b> (#20 DoD).
	 *
	 * <p>이것이 이 파일의 존재 이유다. 쿼리가 <b>실행되어 빈 결과를 주는 것</b>과 <b>애초에
	 * 만들어지지 않는 것</b>은 다르다 — 전자는 나중에 데이터가 생기면 조용히 값을 돌려준다.
	 */
	@Test
	void S5_3_a_jpql_touching_another_store_entity_is_rejected_at_mapping_time() {
		assertThatThrownBy(() -> this.playEntityManagerFactory.createEntityManager()
				.createQuery("SELECT log FROM AiCallLog log"))
				.as("play EMF 가 promptlog 엔티티를 알고 있다 — 스캔 범위가 넓다")
				.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> this.promptLogEntityManagerFactory.createEntityManager()
				.createQuery("SELECT session FROM PlaySession session"))
				.as("promptlog EMF 가 play 엔티티를 알고 있다 — 스캔 범위가 넓다")
				.isInstanceOf(IllegalArgumentException.class);

		// B-07 — 회원이 실재하게 됐다. play 가 user 를 물 수 있으면 I-3 의 한 겹이 사라진다.
		assertThatThrownBy(() -> this.playEntityManagerFactory.createEntityManager()
				.createQuery("SELECT u FROM User u"))
				.as("play EMF 가 identity 엔티티를 알고 있다 — playerRef 우회 경로가 열린다")
				.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> this.identityEntityManagerFactory.createEntityManager()
				.createQuery("SELECT session FROM PlaySession session"))
				.as("identity EMF 가 play 엔티티를 알고 있다 — 스캔 범위가 넓다")
				.isInstanceOf(IllegalArgumentException.class);

		// B-08 — 마지막 스토어. play 가 catalog 엔티티를 물면 세션이 작품 표를 직접 조인한다.
		assertThatThrownBy(() -> this.playEntityManagerFactory.createEntityManager()
				.createQuery("SELECT g FROM Genre g"))
				.as("play EMF 가 catalog 엔티티를 알고 있다 — 파사드를 건너뛰는 경로가 열린다")
				.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> this.catalogEntityManagerFactory.createEntityManager()
				.createQuery("SELECT u FROM User u"))
				.as("catalog EMF 가 identity 엔티티를 알고 있다 — playerRef 우회 경로가 열린다")
				.isInstanceOf(IllegalArgumentException.class);
	}

	/**
	 * <b>조인은 더더욱 만들어지지 않는다.</b>
	 *
	 * <p>단건 조회가 막히면 조인도 막히지만, §5.3 이 실제로 두려워하는 형태를 그대로 써 둔다 —
	 * 나중에 이 파일을 읽는 사람이 <b>무엇을 막고 있는지</b>를 문장이 아니라 코드로 보게 된다.
	 */
	@Test
	void S5_3_a_cross_schema_join_cannot_be_expressed() {
		assertThatThrownBy(() -> this.playEntityManagerFactory.createEntityManager().createQuery("""
				SELECT turn FROM Turn turn, AiCallLog log
				WHERE log.sessionId = turn.sessionId
				"""))
				.isInstanceOf(IllegalArgumentException.class);
	}

	/**
	 * <b>TransactionManager 도 스토어마다다.</b>
	 *
	 * <p>EMF 를 나눠도 매니저가 하나면 한 트랜잭션이 두 스토어에 걸친다. 후보가 둘이 되면서
	 * <b>이름 없는 {@code @Transactional} 이 부팅에서 실패한다</b> — 그 성질이 이제 실제로 작동한다.
	 */
	@Test
	void S5_3_each_store_has_its_own_transaction_manager() {
		assertThat(this.playEntityManagerFactory)
				.isNotSameAs(this.promptLogEntityManagerFactory)
				.isNotSameAs(this.identityEntityManagerFactory);
		assertThat(this.promptLogEntityManagerFactory).isNotSameAs(this.identityEntityManagerFactory);
		assertThat(this.catalogEntityManagerFactory)
				.isNotSameAs(this.playEntityManagerFactory)
				.isNotSameAs(this.identityEntityManagerFactory);
		assertThat(this.identityEntityManagerFactory.getProperties().get("hibernate.hbm2ddl.auto"))
				.as("엔티티와 마이그레이션이 어긋나면 부팅에서 잡혀야 한다")
				.isEqualTo("validate");
		assertThat(this.playEntityManagerFactory.getProperties().get("hibernate.hbm2ddl.auto"))
				.isEqualTo("validate");
	}

	private static Set<String> entityNames(EntityManagerFactory factory) {
		return factory.getMetamodel().getEntities().stream()
				.map(jakarta.persistence.metamodel.EntityType::getName)
				.collect(java.util.stream.Collectors.toSet());
	}
}
