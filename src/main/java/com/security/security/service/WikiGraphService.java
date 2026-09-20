package com.security.security.service;

import com.security.security.dto.WikiGraphCommunityDto;
import com.security.security.dto.WikiHealthDto;
import com.security.security.entity.WikiLink;
import com.security.security.entity.WikiPage;
import com.security.security.entity.mongo.MongoWikiPage;
import com.security.security.repository.WikiLinkRepository;
import com.security.security.repository.WikiPageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.Graph;
import org.jgrapht.alg.clustering.LabelPropagationClustering;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DefaultUndirectedGraph;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.GraphLookupOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class WikiGraphService {

    private final WikiPageRepository wikiPageRepository;
    private final WikiLinkRepository wikiLinkRepository;
    /** Present only under mongodb / mongodb-benchmark profiles. */
    private final ObjectProvider<MongoTemplate> mongoTemplateProvider;

    public WikiGraphCommunityDto detectCommunities(String workspaceId) {
        List<WikiPage> pages = new ArrayList<>(wikiPageRepository.findByWorkspaceId(workspaceId));
        if (!"ALL".equals(workspaceId) && !"GLOBAL".equals(workspaceId)) {
            pages.addAll(wikiPageRepository.findByWorkspaceId("ALL"));
            pages.addAll(wikiPageRepository.findByWorkspaceId("GLOBAL"));
        }
        if (pages.isEmpty()) {
            return WikiGraphCommunityDto.builder()
                    .communities(Collections.emptyList())
                    .bridgeNodes(Collections.emptyList())
                    .build();
        }

        Map<String, Long> slugToId = new HashMap<>();
        Map<Long, String> idToSlug = new HashMap<>();
        Map<Long, WikiPage> idToPage = new HashMap<>();
        for (WikiPage p : pages) {
            slugToId.put(p.getSlug(), p.getId());
            idToSlug.put(p.getId(), p.getSlug());
            idToPage.put(p.getId(), p);
        }

        List<Long> pageIds = pages.stream().map(WikiPage::getId).toList();
        List<WikiLink> allLinks = wikiLinkRepository.findByFromPageIdIn(pageIds);

        Graph<Long, DefaultEdge> graph = new DefaultUndirectedGraph<>(DefaultEdge.class);
        for (WikiPage p : pages) {
            graph.addVertex(p.getId());
        }

        for (WikiLink link : allLinks) {
            Long toId = slugToId.get(link.getToSlug());
            if (toId != null && graph.containsVertex(toId) && !link.getFromPageId().equals(toId)) {
                try {
                    graph.addEdge(link.getFromPageId(), toId);
                } catch (Exception ignored) {
                    // duplicate edge
                }
            }
        }

        // Community detection via Label Propagation (JGraphT built-in, simpler than Louvain)
        LabelPropagationClustering<Long, DefaultEdge> clustering =
                new LabelPropagationClustering<>(graph);
        var result = clustering.getClustering();

        Map<Long, Integer> nodeToCommunity = new HashMap<>();
        List<Set<Long>> clusters = result.getClusters();
        for (int i = 0; i < clusters.size(); i++) {
            for (Long nodeId : clusters.get(i)) {
                nodeToCommunity.put(nodeId, i);
            }
        }

        List<WikiGraphCommunityDto.Community> communities = new ArrayList<>();
        for (int i = 0; i < clusters.size(); i++) {
            Set<Long> cluster = clusters.get(i);
            List<String> slugs = cluster.stream()
                    .map(idToSlug::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            double cohesion = computeCohesion(graph, cluster);

            String topHub = cluster.stream()
                    .max(Comparator.comparingInt(id -> graph.degreeOf(id)))
                    .map(idToSlug::get)
                    .orElse("");

            communities.add(WikiGraphCommunityDto.Community.builder()
                    .id(i)
                    .pageSlugs(slugs)
                    .cohesion(cohesion)
                    .topHub(topHub)
                    .lowCohesion(cohesion < 0.15)
                    .build());
        }

        // Bridge nodes: vertices connecting 3+ different communities
        List<WikiHealthDto.WikiPageRef> bridgeNodes = new ArrayList<>();
        for (WikiPage page : pages) {
            Long nodeId = page.getId();
            Set<Integer> neighborCommunities = new HashSet<>();
            for (DefaultEdge edge : graph.edgesOf(nodeId)) {
                Long neighbor = graph.getEdgeSource(edge).equals(nodeId)
                        ? graph.getEdgeTarget(edge)
                        : graph.getEdgeSource(edge);
                Integer comm = nodeToCommunity.get(neighbor);
                if (comm != null) {
                    neighborCommunities.add(comm);
                }
            }
            if (neighborCommunities.size() >= 3) {
                bridgeNodes.add(WikiHealthDto.WikiPageRef.builder()
                        .slug(page.getSlug())
                        .title(page.getTitle())
                        .pageType(page.getPageType())
                        .updatedAt(page.getUpdatedAt())
                        .build());
            }
        }

        return WikiGraphCommunityDto.builder()
                .communities(communities)
                .bridgeNodes(bridgeNodes)
                .build();
    }

    private double computeCohesion(Graph<Long, DefaultEdge> graph, Set<Long> cluster) {
        if (cluster.size() < 2) return 1.0;

        int intraEdges = 0;
        for (Long nodeId : cluster) {
            for (DefaultEdge edge : graph.edgesOf(nodeId)) {
                Long neighbor = graph.getEdgeSource(edge).equals(nodeId)
                        ? graph.getEdgeTarget(edge)
                        : graph.getEdgeSource(edge);
                if (cluster.contains(neighbor)) {
                    intraEdges++;
                }
            }
        }
        intraEdges /= 2; // each edge counted twice in undirected graph

        int n = cluster.size();
        int possibleEdges = n * (n - 1) / 2;
        return possibleEdges > 0 ? (double) intraEdges / possibleEdges : 0.0;
    }

    public double computeAdamicAdar(Graph<Long, DefaultEdge> graph, Long pageA, Long pageB) {
        Set<Long> neighborsA = new HashSet<>();
        for (DefaultEdge e : graph.edgesOf(pageA)) {
            Long n = graph.getEdgeSource(e).equals(pageA) ? graph.getEdgeTarget(e) : graph.getEdgeSource(e);
            neighborsA.add(n);
        }

        Set<Long> neighborsB = new HashSet<>();
        for (DefaultEdge e : graph.edgesOf(pageB)) {
            Long n = graph.getEdgeSource(e).equals(pageB) ? graph.getEdgeTarget(e) : graph.getEdgeSource(e);
            neighborsB.add(n);
        }

        double score = 0.0;
        for (Long common : neighborsA) {
            if (neighborsB.contains(common)) {
                int degree = graph.degreeOf(common);
                if (degree > 1) {
                    score += 1.0 / Math.log(degree);
                }
            }
        }
        return score;
    }

    /**
     * Multi-hop wiki reachability via MongoDB {@code $graphLookup} on embedded {@code outboundSlugs}.
     * Requires MongoTemplate (profiles mongodb / mongodb-benchmark).
     *
     * @return reachable page slugs (excluding start), empty if Mongo unavailable or start missing
     */
    public List<String> graphLookupReachable(String startSlug, int maxDepth) {
        MongoTemplate mongoTemplate = mongoTemplateProvider.getIfAvailable();
        if (mongoTemplate == null) {
            log.warn("[WikiGraphService] MongoTemplate not available — graphLookupReachable skipped");
            return List.of();
        }
        if (startSlug == null || startSlug.isBlank()) {
            return List.of();
        }

        int depth = Math.max(0, maxDepth - 1);
        GraphLookupOperation graphLookup = GraphLookupOperation.builder()
                .from("wiki_pages")
                .startWith("outboundSlugs")
                .connectFrom("outboundSlugs")
                .connectTo("slug")
                .maxDepth(depth)
                .depthField("hopCount")
                .as("reachablePages");

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("slug").is(startSlug)),
                graphLookup
        );

        List<org.bson.Document> results = mongoTemplate.aggregate(
                aggregation, "wiki_pages", org.bson.Document.class).getMappedResults();

        LinkedHashSet<String> slugs = new LinkedHashSet<>();
        for (org.bson.Document doc : results) {
            Object reachable = doc.get("reachablePages");
            if (!(reachable instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (item instanceof org.bson.Document pageDoc) {
                    String slug = pageDoc.getString("slug");
                    if (slug != null && !slug.equals(startSlug)) {
                        slugs.add(slug);
                    }
                } else if (item instanceof MongoWikiPage mwp && mwp.getSlug() != null
                        && !mwp.getSlug().equals(startSlug)) {
                    slugs.add(mwp.getSlug());
                }
            }
        }
        log.debug("[WikiGraphService] $graphLookup start={} depth={} reachable={}",
                startSlug, maxDepth, slugs.size());
        return new ArrayList<>(slugs);
    }

    /**
     * In-memory multi-hop reachability using JPA wiki_pages + wiki_links (PostgreSQL baseline).
     */
    public List<String> jgraphtReachable(String startSlug, int maxDepth) {
        List<WikiPage> pages = wikiPageRepository.findAll();
        Map<String, Long> slugToId = new HashMap<>();
        Map<Long, String> idToSlug = new HashMap<>();
        for (WikiPage p : pages) {
            slugToId.put(p.getSlug(), p.getId());
            idToSlug.put(p.getId(), p.getSlug());
        }
        Long startId = slugToId.get(startSlug);
        if (startId == null) {
            return List.of();
        }

        Map<Long, List<Long>> adj = new HashMap<>();
        for (WikiLink link : wikiLinkRepository.findAll()) {
            Long toId = slugToId.get(link.getToSlug());
            if (toId != null) {
                adj.computeIfAbsent(link.getFromPageId(), k -> new ArrayList<>()).add(toId);
            }
        }

        Set<String> reachable = new LinkedHashSet<>();
        List<Long> frontier = new ArrayList<>();
        frontier.add(startId);
        Set<Long> visited = new HashSet<>();
        visited.add(startId);
        for (int d = 0; d < maxDepth && !frontier.isEmpty(); d++) {
            List<Long> next = new ArrayList<>();
            for (Long node : frontier) {
                for (Long neigh : adj.getOrDefault(node, List.of())) {
                    if (visited.add(neigh)) {
                        String slug = idToSlug.get(neigh);
                        if (slug != null) {
                            reachable.add(slug);
                        }
                        next.add(neigh);
                    }
                }
            }
            frontier = next;
        }
        return new ArrayList<>(reachable);
    }
}
