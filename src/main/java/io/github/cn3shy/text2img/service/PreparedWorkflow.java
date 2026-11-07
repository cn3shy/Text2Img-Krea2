package io.github.cn3shy.text2img.service;

import java.util.Map;
import tools.jackson.databind.node.ObjectNode;

/**
 * 提交前准备好的 workflow，以及节点 id → 阶段文案的映射（用于进度展示）。
 */
public record PreparedWorkflow(ObjectNode workflow, Map<String, String> nodeStages) {
}
