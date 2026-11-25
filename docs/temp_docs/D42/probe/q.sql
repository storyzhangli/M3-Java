SELECT document_id, status, lifecycle_status, version FROM knowledge_document WHERE document_id LIKE 'DOC-%';
SELECT document_id, COUNT(*) c FROM document_chunk_index WHERE document_id LIKE 'DOC-%' AND status='ACTIVE' GROUP BY document_id;
