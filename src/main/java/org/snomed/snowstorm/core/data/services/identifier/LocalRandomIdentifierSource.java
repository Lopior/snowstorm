package org.snomed.snowstorm.core.data.services.identifier;

import com.google.common.collect.Lists;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import org.snomed.snowstorm.core.data.domain.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.kaicode.elasticvc.helper.QueryHelper.termsQuery;

/**
 * Generates SNOMED Component identifiers locally using random numbers.
 * The store is queried to check that the numbers are unique.
 */
public class LocalRandomIdentifierSource implements IdentifierSource {

	public static final String POSTCOORDINATED_EXPRESSION_PARTITION_ID = "16";

	private final ElasticsearchOperations elasticsearchOperations;

	private ItemIdProvider itemIdProvider;

	public LocalRandomIdentifierSource(ElasticsearchOperations elasticsearchOperations) {
		this.elasticsearchOperations = elasticsearchOperations;
		itemIdProvider = new RandomItemIdProvider();
	}

	@Override
	public List<Long> reserveIds(int namespaceId, String partitionId, int quantity) {
		Set<Long> newIdentifiers = new LongLinkedOpenHashSet();
		List<Long> newIdentifierList = null;
		do {
			String hackId = itemIdProvider.getItemIdentifier();
			String namespace = namespaceId == 0 ? "" : namespaceId + "";
			String sctidWithoutCheck = hackId + namespace + partitionId;
			char verhoeff = VerhoeffCheck.calculateChecksum(sctidWithoutCheck, 0, false);
			long newSctid = Long.parseLong(sctidWithoutCheck + verhoeff);
			newIdentifiers.add(newSctid);
			if (newIdentifiers.size() == quantity) {
				newIdentifierList = new LongArrayList(newIdentifiers);
				// Bulk unique check
				List<Long> alreadyExistingIdentifiers = new LongArrayList();
				for (List<Long> newIdentifierBatch : Lists.partition(newIdentifierList, 10_000)) {
					switch (partitionId) {
						case "00", "10" ->
							// Concept identifier
							alreadyExistingIdentifiers.addAll(findExistingIdentifiersInAnyBranch(newIdentifierBatch, Concept.class, Concept.Fields.CONCEPT_ID));
						case "01", "11" ->
							// Description identifier
							alreadyExistingIdentifiers.addAll(findExistingIdentifiersInAnyBranch(newIdentifierBatch, Description.class, Description.Fields.DESCRIPTION_ID));
						case "02", "12" ->
							// Relationship identifier
							alreadyExistingIdentifiers.addAll(findExistingIdentifiersInAnyBranch(newIdentifierBatch, Relationship.class, Relationship.Fields.RELATIONSHIP_ID));
						case "16" ->
							// Expression identifier
							alreadyExistingIdentifiers.addAll(findExistingIdentifiersInAnyBranch(newIdentifierBatch, ReferenceSetMember.class, ReferenceSetMember.Fields.REFERENCED_COMPONENT_ID));
					}
				}
				// Remove any identifiers which already exist in storage - more will be generated in the next loop.
				newIdentifiers.removeAll(alreadyExistingIdentifiers);
			}
		} while (newIdentifiers.size() < quantity);

		return newIdentifierList;
	}

	// Finds and returns matching existing identifiers
	private List<Long> findExistingIdentifiersInAnyBranch(List<Long> identifiers, Class<? extends SnomedComponent> snomedComponentClass, String idField) {
		NativeQueryBuilder queryBuilder = new NativeQueryBuilder()
				.withQuery(termsQuery(idField, identifiers))
				.withPageable(PageRequest.of(0, identifiers.size()));
		return elasticsearchOperations.search(queryBuilder.build(), snomedComponentClass)
				.stream().map(hit -> Long.parseLong(hit.getContent().getId())).collect(Collectors.toList());
	}

	@Override
	public void registerIds(int namespace, Collection<Long> idsAssigned) {
		// Not required for this implementation.
	}

	public ItemIdProvider getItemIdProvider() {
		return itemIdProvider;
	}

	void setItemIdProvider(ItemIdProvider itemIdProvider) {
		this.itemIdProvider = itemIdProvider;
	}

	private static final class RandomItemIdProvider implements ItemIdProvider {

		// Generates a string of a random number with a guaranteed length of 8 digits.
		@Override
		public String getItemIdentifier() {
			String id;
			do {
				id = "" + Math.round(Math.random() * 10000000000f);
			} while (id.length() < 8);

			return id.substring(0, 8);
		}

	}

	interface ItemIdProvider {
		String getItemIdentifier();
	}
}
