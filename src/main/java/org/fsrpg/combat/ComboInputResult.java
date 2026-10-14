package org.fsrpg.combat;

/** Buffered/queued input is accepted as intent only; it does not spend resources or start a skill. */
public record ComboInputResult(ComboInputStatus status, ActionResult rejection, int stage, String skillId) {
    public boolean accepted() {
        return status == ComboInputStatus.STARTED || status == ComboInputStatus.BUFFERED || status == ComboInputStatus.QUEUED
                || status == ComboInputStatus.ALREADY_QUEUED && rejection == ActionResult.ACCEPTED;
    }
    public boolean started() { return status == ComboInputStatus.STARTED; }
}
