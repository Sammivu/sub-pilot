package co.subpilot.plan;
 
/**
 * Aligned with frontend BACKEND_HANDOFF.md: draft -> published -> archived
 * (NOT the PRD's "active" — frontend UI explicitly uses "published").
 */
public enum PlanStatus {
    draft,
    published,
    archived
}