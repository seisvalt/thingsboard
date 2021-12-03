/**
 * Copyright © 2016-2021 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.rule;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.flow.TbRuleChainInputNode;
import org.thingsboard.rule.engine.flow.TbRuleChainInputNodeConfiguration;
import org.thingsboard.rule.engine.flow.TbRuleChainOutputNode;
import org.thingsboard.rule.engine.flow.TbRuleChainOutputNodeConfiguration;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.id.RuleNodeId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.common.data.rule.RuleChain;
import org.thingsboard.server.common.data.rule.RuleChainMetaData;
import org.thingsboard.server.common.data.rule.RuleChainOutputLabelsUsage;
import org.thingsboard.server.common.data.rule.RuleChainUpdateResult;
import org.thingsboard.server.common.data.rule.RuleNode;
import org.thingsboard.server.common.data.rule.RuleNodeUpdateResult;
import org.thingsboard.server.dao.relation.RelationService;
import org.thingsboard.server.dao.rule.RuleChainService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.permission.Operation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
@TbCoreComponent
@Slf4j
public class DefaultTbRuleChainService implements TbRuleChainService {

    private final RuleChainService ruleChainService;
    private final RelationService relationService;

    @Override
    public Set<String> getRuleChainOutputLabels(TenantId tenantId, RuleChainId ruleChainId) {
        RuleChainMetaData metaData = ruleChainService.loadRuleChainMetaData(tenantId, ruleChainId);
        Set<String> outputLabels = new TreeSet<>();
        for (RuleNode ruleNode : metaData.getNodes()) {
            if (isOutputRuleNode(ruleNode)) {
                try {
                    var configuration = JacksonUtil.treeToValue(ruleNode.getConfiguration(), TbRuleChainOutputNodeConfiguration.class);
                    if (StringUtils.isNotEmpty(configuration.getLabel())) {
                        outputLabels.add(configuration.getLabel());
                    }
                } catch (Exception e) {
                    log.warn("[{}][{}] Failed to decode rule node configuration", tenantId, ruleChainId, e);
                }
            }
        }
        return outputLabels;
    }

    @Override
    public List<RuleChainOutputLabelsUsage> getOutputLabelUsage(TenantId tenantId, RuleChainId ruleChainId) {
        List<RuleNode> ruleNodes = ruleChainService.findRuleNodesByTenantIdAndType(tenantId, TbRuleChainInputNode.class.getName(), ruleChainId.getId().toString());
        Map<RuleChainId, String> ruleChainNamesCache = new HashMap<>();
        // Additional filter, "just in case" the structure of the JSON configuration will change.
        var filteredRuleNodes = ruleNodes.stream().filter(node -> {
            try {
                TbRuleChainInputNodeConfiguration configuration = JacksonUtil.treeToValue(node.getConfiguration(), TbRuleChainInputNodeConfiguration.class);
                return ruleChainId.getId().toString().equals(configuration.getRuleChainId());
            } catch (Exception e) {
                log.warn("[{}][{}] Failed to decode rule node configuration", tenantId, ruleChainId, e);
                return false;
            }
        }).collect(Collectors.toList());


        return filteredRuleNodes.stream()
                .map(ruleNode -> {
                    RuleChainOutputLabelsUsage usage = new RuleChainOutputLabelsUsage();
                    usage.setRuleNodeId(ruleNode.getId());
                    usage.setRuleNodeName(ruleNode.getName());
                    usage.setRuleChainId(ruleNode.getRuleChainId());
                    List<EntityRelation> relations = ruleChainService.getRuleNodeRelations(tenantId, ruleNode.getId());
                    if (relations != null && !relations.isEmpty()) {
                        usage.setLabels(relations.stream().map(EntityRelation::getType).collect(Collectors.toSet()));
                    }
                    return usage;
                })
                .filter(usage -> usage.getLabels() != null)
                .peek(usage -> {
                    String ruleChainName = ruleChainNamesCache.computeIfAbsent(usage.getRuleChainId(),
                            id -> ruleChainService.findRuleChainById(tenantId, id).getName());
                    usage.setRuleChainName(ruleChainName);
                })
                .sorted(Comparator
                        .comparing(RuleChainOutputLabelsUsage::getRuleChainName)
                        .thenComparing(RuleChainOutputLabelsUsage::getRuleNodeName))
                .collect(Collectors.toList());
    }

    @Override
    public void updateRelatedRuleChains(TenantId tenantId, RuleChainId ruleChainId, RuleChainUpdateResult result) {
        log.debug("[{}][{}] Going to update links in related rule chains", tenantId, ruleChainId);
        if (result.getUpdatedRuleNodes() == null || result.getUpdatedRuleNodes().isEmpty()) {
            return;
        }

        Set<String> oldLabels = new HashSet<>();
        Set<String> newLabels = new HashSet<>();
        Set<String> confusedLabels = new HashSet<>();
        Map<String, String> updatedLabels = new HashMap<>();
        for (RuleNodeUpdateResult update : result.getUpdatedRuleNodes()) {
            var node = update.getNewRuleNode();
            if (isOutputRuleNode(node)) {
                try {
                    TbRuleChainOutputNodeConfiguration oldConf = JacksonUtil.treeToValue(update.getOldConfiguration(), TbRuleChainOutputNodeConfiguration.class);
                    TbRuleChainOutputNodeConfiguration newConf = JacksonUtil.treeToValue(node.getConfiguration(), TbRuleChainOutputNodeConfiguration.class);
                    oldLabels.add(oldConf.getLabel());
                    newLabels.add(newConf.getLabel());
                    if (!oldConf.getLabel().equals(newConf.getLabel())) {
                        String oldLabel = oldConf.getLabel();
                        String newLabel = newConf.getLabel();
                        if (updatedLabels.containsKey(oldLabel) && !updatedLabels.get(oldLabel).equals(newLabel)) {
                            confusedLabels.add(oldLabel);
                            log.warn("[{}][{}] Can't automatically rename the label from [{}] to [{}] due to conflict [{}]", tenantId, ruleChainId, oldLabel, newLabel, updatedLabels.get(oldLabel));
                        } else {
                            updatedLabels.put(oldLabel, newLabel);
                        }

                    }
                } catch (Exception e) {
                    log.warn("[{}][{}][{}] Failed to decode rule node configuration", tenantId, ruleChainId, node.getId(), e);
                }
            }
        }
        // Remove all output labels that are renamed to two or more different labels, since we don't which new label to use;
        confusedLabels.forEach(updatedLabels::remove);
        // Remove all output labels that are renamed but still present in the rule chain;
        newLabels.forEach(updatedLabels::remove);
        if (!oldLabels.equals(newLabels)) {
            updateRelatedRuleChains(tenantId, ruleChainId, updatedLabels);
        }
    }

    public void updateRelatedRuleChains(TenantId tenantId, RuleChainId ruleChainId, Map<String, String> labelsMap) {
        List<RuleChainOutputLabelsUsage> usageList = getOutputLabelUsage(tenantId, ruleChainId);
        for (RuleChainOutputLabelsUsage usage : usageList) {
            labelsMap.forEach((oldLabel, newLabel) -> {
                if (usage.getLabels().contains(oldLabel)) {
                    renameOutgoingLinks(tenantId, usage.getRuleNodeId(), oldLabel, newLabel);
                }
            });
        }
    }

    private void renameOutgoingLinks(TenantId tenantId, RuleNodeId ruleNodeId, String oldLabel, String newLabel) {
        List<EntityRelation> relations = ruleChainService.getRuleNodeRelations(tenantId, ruleNodeId);
        for (EntityRelation relation : relations) {
            if (relation.getType().equals(oldLabel)) {
                relationService.deleteRelation(tenantId, relation);
                relation.setType(newLabel);
                relationService.saveRelation(tenantId, relation);
            }
        }
    }

    private boolean isOutputRuleNode(RuleNode ruleNode) {
        return isRuleNode(ruleNode, TbRuleChainOutputNode.class);
    }

    private boolean isInputRuleNode(RuleNode ruleNode) {
        return isRuleNode(ruleNode, TbRuleChainInputNode.class);
    }

    private boolean isRuleNode(RuleNode ruleNode, Class<?> clazz) {
        return ruleNode != null && ruleNode.getType().equals(clazz.getName());
    }
}
