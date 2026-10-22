package org.stg.savan.savanserver.service.support;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.stg.savan.savanserver.constants.SathiConstants;
import org.stg.savan.savanserver.model.MemoryType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Corpus reads for meeting-wide synthesis, using the same table as Spring AI's pgvector store. */
@Repository
public class PgVectorMeetingMemoryRepository implements MeetingMemoryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final String tableName;

    public PgVectorMeetingMemoryRepository(
            JdbcTemplate jdbcTemplate,
            @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}") String tableName) {
        if (tableName == null || !tableName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid pgvector table name");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.tableName = tableName;
    }

    @Override
    public List<Document> findTranscriptChunks(String meetingId) {
        String sql = selectDocuments() + " WHERE metadata->>'pipeline' = ?"
                + " AND metadata->>'memory_type' = ?"
                + " AND metadata->>'meeting_id' = ?"
                + orderByChunk();
        return jdbcTemplate.query(sql, this::mapDocument,
                SathiConstants.CHAT_PIPELINE, MemoryType.TRANSCRIPT.getMetadataValue(), meetingId);
    }

    @Override
    public List<Document> findAllTranscriptChunks() {
        String sql = selectDocuments() + " WHERE metadata->>'pipeline' = ?"
                + " AND metadata->>'memory_type' = ?" + orderByMeetingAndChunk();
        return jdbcTemplate.query(sql, this::mapDocument,
                SathiConstants.CHAT_PIPELINE, MemoryType.TRANSCRIPT.getMetadataValue());
    }

    @Override
    public List<Document> findAllMeetingSummaries() {
        String sql = selectDocuments() + " WHERE metadata->>'pipeline' = ?"
                + " AND metadata->>'memory_type' = ?"
                + " ORDER BY metadata->>'meeting_id'";
        return jdbcTemplate.query(sql, this::mapDocument,
                SathiConstants.CHAT_PIPELINE, MemoryType.MEETING_SUMMARY.getMetadataValue());
    }

    private String selectDocuments() {
        return "SELECT id, content, metadata->>'meeting_id' AS meeting_id, "
                + "metadata->>'pipeline' AS pipeline, metadata->>'memory_type' AS memory_type, "
                + "metadata->>'chunk_index' AS chunk_index, "
                + "metadata->>'parent_document_id' AS parent_document_id, "
                + "metadata->>'received_at' AS received_at, metadata->>'device' AS device FROM " + tableName;
    }

    private String orderByChunk() {
        return " ORDER BY COALESCE(NULLIF(metadata->>'chunk_index', '')::integer, 0)";
    }

    private String orderByMeetingAndChunk() {
        return " ORDER BY metadata->>'meeting_id', "
                + "COALESCE(NULLIF(metadata->>'chunk_index', '')::integer, 0)";
    }

    private Document mapDocument(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        Map<String, Object> metadata = new HashMap<>();
        for (String key : List.of("meeting_id", "pipeline", "memory_type", "parent_document_id", "received_at", "device")) {
            String value = resultSet.getString(key);
            if (value != null) {
                metadata.put(key, value);
            }
        }
        String chunkIndex = resultSet.getString("chunk_index");
        if (chunkIndex != null) {
            metadata.put("chunk_index", Integer.valueOf(chunkIndex));
        }
        return Document.builder()
                .id(resultSet.getString("id"))
                .text(resultSet.getString("content"))
                .metadata(metadata)
                .build();
    }
}
