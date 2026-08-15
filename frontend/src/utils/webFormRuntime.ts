import type { QuestionResponse, SaveAnswerRequest } from '@/stores/webForm'

/**
 * Build the backend answer payload from the exact JavaRosa question context.
 *
 * question.name is only the leaf node name (for example, "name"). It is not
 * sufficient for grouped or repeated questions. The API already returns the
 * canonical question path plus the enclosing repeat path/multiplicity, so the
 * browser must preserve that context when saving an answer.
 */
export function buildSaveAnswerRequest(
  question: QuestionResponse,
  value: unknown,
): SaveAnswerRequest {
  return {
    questionPath: question.path || question.name,
    instancePath: question.instancePath ?? undefined,
    value,
    repeatPath: question.repeatPath ?? undefined,
    repeatIndex: question.repeatIndex ?? undefined,
  }
}
