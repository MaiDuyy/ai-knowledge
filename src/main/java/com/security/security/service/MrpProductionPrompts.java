package com.security.security.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Single source of truth for MRP production prompts / schemas used by
 * {@link MrpPipelineService} and benchmark Ragas export ({@link BenchmarkDataExporter}).
 */
public final class MrpProductionPrompts {

    public static final String PROMPT_VERSION = "mrp-map-v1";

    /** JSON Schema for MAP phase structured output (production). */
    public static final String MAP_PHASE_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "entities": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "name": { "type": "string" },
                  "type": { "type": "string" },
                  "description": { "type": "string" }
                },
                "required": ["name", "type", "description"]
              }
            },
            "concepts": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "name": { "type": "string" },
                  "description": { "type": "string" }
                },
                "required": ["name", "description"]
              }
            },
            "claims": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "subject": { "type": "string" },
                  "claim": { "type": "string" },
                  "sourceContext": { "type": "string" }
                },
                "required": ["subject", "claim", "sourceContext"]
              }
            },
            "contradictions": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "subject": { "type": "string" },
                  "claim_a": { "type": "string" },
                  "claim_b": { "type": "string" },
                  "resolution": { "type": "string" }
                },
                "required": ["subject", "claim_a", "claim_b"]
              }
            },
            "recommendations": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "title": { "type": "string" },
                  "description": { "type": "string" },
                  "priority": { "type": "string" }
                },
                "required": ["title", "description", "priority"]
              }
            }
          },
          "required": ["entities", "concepts", "claims", "contradictions", "recommendations"]
        }
        """;

    /**
     * Production MAP system prompt (sent to LLM for each chunk).
     * Keep in sync with extraction behaviour used in evaluation exports.
     */
    public static final String MAP_SYSTEM_PROMPT = """
            You are an expert enterprise knowledge extraction agent.
            Your job is to read the provided text chunk and extract key structured details.

            CRITICAL GROUNDEDNESS DIRECTIVES:
            - You must ONLY extract entities, concepts, and claims that are explicitly mentioned in the provided text chunk.
            - Do NOT use any external background knowledge, prior assumptions, or web search facts to write descriptions or definitions.
            - The description/definition of each entity or concept MUST be constructed solely from the facts provided in the text. If the text does not describe the entity, use a minimal description derived strictly from the text context, or leave it brief.
            - Every claim's 'claim' and 'sourceContext' fields MUST correspond to the exact facts and sentences in the text chunk. Do NOT extrapolate or assume anything.

            EXTRACTION SCOPE & SUBSTANCE FILTER:
            - Only extract entities/concepts that are substantively discussed (meaning they are mentioned at least twice or receive a dedicated paragraph or multi-sentence explanation).
            - Do NOT extract technology stacks mentioned in passing (e.g. lists like "Tech stack: Spring Boot, MySQL, Redis" — do NOT extract these as separate entities unless they have explicit details in the text).
            - Skip trivial, generic, or name-dropped items.

            NAMING DIRECTIVES:
            - Keep names and types of entities/concepts extremely concise, short, and normalized (e.g. use standard acronyms or clean nouns like "JWT" or "Docker"; do NOT put full sentences, descriptions, or explanations in the name).
            - Do NOT place the same item in both "entities" and "concepts". Place specific named things (people, products, organizations, software tools) in "entities", and abstract theories, methodologies, or design patterns in "concepts".

            IMAGE DIRECTIVES:
            - If the text contains image markers of the form ![caption](image://<uuid>) or ![caption](url), you MUST capture and preserve them verbatim within the description of the entity/concept or the sourceContext of the claim where they appear contextually. Do NOT alter the UUID or the image:// prefix.

            JSON FORMATTING RULES:
            - **CRITICAL**: Do NOT use literal newline characters inside JSON string values. If you need a newline in a string, you MUST use the escaped sequence '\\n'.

            You must extract:
            1. Entities: Organizations, products, technologies, tools, platforms, or people. Give each a clear description.
            2. Concepts: Core paradigms, frameworks, architectural designs, procedures, rules, policies. Define each precisely.
            3. Claims: Facts, guidelines, configurations, assertions, metrics, or requirements. Detail each claim and link it to the subject.
            4. Contradictions: Cases where the text contains conflicting claims about the same subject. Note both sides and any resolution if the text provides one. If none found, return empty array.
            5. Recommendations: Actionable suggestions, improvement proposals, or best practices found in text. Classify priority as HIGH, MEDIUM, or LOW. If none found, return empty array.

            You must return ONLY a valid JSON object. Do NOT wrap the response in markdown blocks (such as ```json). Do NOT add any conversational text before or after the JSON.
            {
              "entities": [
                {"name": "Entity Name", "type": "organization/technology/etc", "description": "Concise description of the entity"}
              ],
              "concepts": [
                {"name": "Concept Name", "description": "Precise definition of this concept"}
              ],
              "claims": [
                {"subject": "Entity/Concept name", "claim": "Fact, metric, assertion, or config", "sourceContext": "Exact text sentence or clear context"}
              ],
              "contradictions": [
                {"subject": "Topic", "claim_a": "First conflicting claim", "claim_b": "Second conflicting claim", "resolution": "Resolution if any"}
              ],
              "recommendations": [
                {"title": "Action title", "description": "What should be done", "priority": "HIGH/MEDIUM/LOW"}
              ]
            }
            """;

    /**
     * Ragas claim-faithfulness task prompt (evaluation export only — NOT sent to MRP).
     * Aligns with production groundedness: claim must be supported by source only.
     */
    public static final String RAGAS_CLAIM_FAITHFULNESS_TASK = """
            You are verifying a claim produced by the SecWiki MRP MAP extractor.
            Production rule: claims must be fully grounded in the source passage only (no external knowledge).
            Decide whether the CLAIM is fully supported by the CONTEXT. Do not reward partial or extrapolated statements.
            """;

    /**
     * Ragas / MAP structured extraction task prompt (evaluation export only).
     * Mirrors production MAP goals without embedding the full system prompt into every JSONL row.
     */
    public static final String RAGAS_STRUCTURED_EXTRACTION_TASK = """
            Extract enterprise knowledge from the source document using the SecWiki MRP MAP schema:
            entities (name, type, description), concepts (name, description),
            claims (subject, claim, sourceContext), contradictions, recommendations (title, description, priority).
            Follow production groundedness: only facts explicitly present in the source.
            """;

    /**
     * Ragas post-Publish wiki task (evaluation export only — NOT sent to MRP).
     * Used as {@code user_input} when scoring published Markdown WikiPages.
     * Metrics: faithfulness, answer_relevancy, context_recall.
     */
    public static final String RAGAS_PUBLISH_WIKI_TASK = """
            Compile a faithful technical Wiki page for the SecWiki knowledge base from the source document.
            Rules:
            - Only include facts explicitly present in the source (no external knowledge).
            - Cover the required technical topics and claims from the enterprise procedure.
            - Prefer precise wikilinks [[slug]] for known concepts when they appear in the source.
            - Do not invent policies, stack items, or metrics not stated in the source.
            """;

    private MrpProductionPrompts() {}

    public static String mapPromptSha256() {
        return sha256Hex(MAP_SYSTEM_PROMPT);
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            return "hash-unavailable";
        }
    }
}
