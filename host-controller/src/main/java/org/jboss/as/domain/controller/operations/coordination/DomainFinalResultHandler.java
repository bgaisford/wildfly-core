/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.domain.controller.operations.coordination;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.COMPOSITE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DOMAIN_FAILURE_DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DOMAIN_RESULTS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILED;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILURE_DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST_FAILURE_DESCRIPTIONS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OUTCOME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESPONSE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER_GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.STEPS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUCCESS;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jboss.as.controller.CompositeOperationHandler;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.domain.controller.ServerIdentity;
import org.jboss.as.domain.controller.logging.DomainControllerLogger;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * Assembles the overall result for a domain operation from individual host and server results.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public class DomainFinalResultHandler implements OperationStepHandler {

    private final DomainOperationContext domainOperationContext;

    public DomainFinalResultHandler(DomainOperationContext domainOperationContext) {
        this.domainOperationContext = domainOperationContext;
    }

    @Override
    public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {

        context.completeStep(new OperationContext.ResultHandler() {
            @Override
            public void handleResult(OperationContext.ResultAction resultAction, OperationContext context, ModelNode operation) {
                // On the way out, fix up the response
                final boolean isDomain = isDomainOperation(operation);
                boolean shouldContinue = collectDomainFailure(context, isDomain);
                shouldContinue = shouldContinue && collectContextFailure(context, isDomain);
                if (shouldContinue) {

                    ModelNode contextResult = context.getResult();
                    contextResult.setEmptyObject(); // clear out any old data
                    contextResult.set(getDomainResults(operation));

                    // If we have server results we know all was ok on the slaves
                    Map<ServerIdentity, ModelNode> serverResults = domainOperationContext.getServerResults();
                    if (serverResults.size() > 0) {
                        populateServerGroupResults(context, serverResults);
                    } else {
                        shouldContinue = collectHostFailures(context, isDomain);
                        if (shouldContinue) {
                            // Just make sure there's an 'undefined' server-groups node
                            context.getServerResults();
                        }
                    }
                }

                if (!shouldContinue && context.hasResult()) {
                    context.getResult().setEmptyObject();  // clear out any old data
                }
            }
        });
    }

    private boolean collectDomainFailure(OperationContext context, final boolean isDomain) {
        final ModelNode coordinator = domainOperationContext.getCoordinatorResult();
        ModelNode domainFailure = null;
        if (isDomain &&  coordinator != null && coordinator.has(FAILURE_DESCRIPTION)) {
            domainFailure = coordinator.hasDefined(FAILURE_DESCRIPTION) ? coordinator.get(FAILURE_DESCRIPTION) : new ModelNode(DomainControllerLogger.ROOT_LOGGER.unexplainedFailure());
        }
        if (domainFailure != null) {
            ModelNode fullFailure = new ModelNode();
            fullFailure.get(DOMAIN_FAILURE_DESCRIPTION).set(domainFailure);
            context.getFailureDescription().set(fullFailure);
            return false;
        }
        return true;
    }

    private boolean collectContextFailure(OperationContext context, final boolean isDomain) {
        // We ignore a context failure description if the request failed on all servers, as the
        // DomainRolloutStepHandler would have had to set that to trigger model rollback
        // but we still want to record the server results so the user can see the problem
        if (!domainOperationContext.isFailureReported() && context.hasFailureDescription()) {
            ModelNode formattedFailure = new ModelNode();
            if (isDomain) {
                ModelNode failure = context.getFailureDescription();
                if (failure.isDefined())
                    formattedFailure.get(DOMAIN_FAILURE_DESCRIPTION).set(failure);
                else
                    formattedFailure.get(DOMAIN_FAILURE_DESCRIPTION).set(DomainControllerLogger.ROOT_LOGGER.unexplainedFailure());
            } else {
                ModelNode hostFailureProperty = new ModelNode();
                ModelNode contextFailure = context.getFailureDescription();
                ModelNode hostFailure = contextFailure.isDefined() ? contextFailure : new ModelNode(DomainControllerLogger.ROOT_LOGGER.unexplainedFailure());
                hostFailureProperty.add(domainOperationContext.getLocalHostInfo().getLocalHostName(), hostFailure);

                formattedFailure.get(HOST_FAILURE_DESCRIPTIONS).set(hostFailureProperty);
            }
            context.getFailureDescription().set(formattedFailure);

            return false;
        }
        return true;
    }

    private boolean collectHostFailures(final OperationContext context, final boolean isDomain) {
        ModelNode hostFailureResults = null;
        for (Map.Entry<String, ModelNode> entry : domainOperationContext.getHostControllerResults().entrySet()) {
            ModelNode hostResult = entry.getValue();
            if (hostResult.has(FAILURE_DESCRIPTION)) {
                if (hostFailureResults == null) {
                    hostFailureResults = new ModelNode();
                }
                final ModelNode desc = hostResult.hasDefined(FAILURE_DESCRIPTION) ? hostResult.get(FAILURE_DESCRIPTION) : new ModelNode().set(DomainControllerLogger.ROOT_LOGGER.unexplainedFailure());
                hostFailureResults.get(entry.getKey()).set(desc);
            }
        }

        final ModelNode coordinator = domainOperationContext.getCoordinatorResult();
        if (!isDomain && coordinator != null && coordinator.has(FAILURE_DESCRIPTION)) {
            if (hostFailureResults == null) {
                hostFailureResults = new ModelNode();
            }
            final ModelNode desc = coordinator.hasDefined(FAILURE_DESCRIPTION) ? coordinator.get(FAILURE_DESCRIPTION) : new ModelNode().set(DomainControllerLogger.ROOT_LOGGER.unexplainedFailure());
            hostFailureResults.get(domainOperationContext.getLocalHostInfo().getLocalHostName()).set(desc);
        }

        if (hostFailureResults != null) {
            //context.getFailureDescription().get(HOST_FAILURE_DESCRIPTIONS).set(hostFailureResults);

            //The following is a workaround for AS7-4597
            //DomainRolloutStepHandler.pushToServers() puts in a simple string into the failure description, but that might be a red herring.
            //If there is a failure description and it is not of type OBJECT, then let's not set it for now
            if (!context.getFailureDescription().isDefined() || context.getFailureDescription().getType() == ModelType.OBJECT) {
                ModelNode fullFailure = new ModelNode();
                fullFailure.get(HOST_FAILURE_DESCRIPTIONS).set(hostFailureResults);
                context.getFailureDescription().set(fullFailure);
            } else {
                DomainControllerLogger.CONTROLLER_LOGGER.debugf("Failure description is not of type OBJECT '%s'", context.getFailureDescription());
            }
            return false;
        }
        return true;
    }

    private ModelNode getDomainResults(final ModelNode operation, final String... stepLabels) {
        ResponseProvider provider = new ResponseProvider(operation, domainOperationContext.getLocalHostInfo().getLocalHostName());
        ModelNode result;
        if (!provider.isLeaf()) {
            result = new ModelNode();
            String[] nextStepLabels = new String[stepLabels.length + 1];
            System.arraycopy(stepLabels, 0, nextStepLabels, 0, stepLabels.length);
            int i = 1;

            for (ModelNode step : provider.getChildren()) {
                String childStepLabel = "step-" + i++;
                nextStepLabels[stepLabels.length] = childStepLabel;
                result.get(childStepLabel).set(getDomainResults(step, nextStepLabels));
            }
        } else if (provider.getServer() == null) {
            String hostName = provider.getHost();
            if (hostName.equals("*")) {
                result = new ModelNode();
                for (final Map.Entry<String, ModelNode> hostEntry : domainOperationContext.getHostControllerResults().entrySet()) {
                    final String host = hostEntry.getKey();
                    boolean forMaster = host.equals(domainOperationContext.getLocalHostInfo().getLocalHostName());
                    ModelNode hostResponse = forMaster ? domainOperationContext.getCoordinatorResult() : domainOperationContext.getHostControllerResults().get(hostName);
                    if (hostResponse != null) {
                        result.add(getHostControllerResult(hostResponse, stepLabels));
                    }
                }
            } else {
                boolean forMaster = hostName.equals(domainOperationContext.getLocalHostInfo().getLocalHostName());
                ModelNode hostResponse = forMaster ? domainOperationContext.getCoordinatorResult()  : domainOperationContext.getHostControllerResults().get(hostName);
                result = getHostControllerResult(hostResponse, stepLabels);
            }
        } else {
//            if (provider.getServer().equals("*")) {
//                result = getHostControllerResult(domainOperationContext.getCoordinatorResult(), stepLabels);
//            } else {
//                result = domainOperationContext.getServerResult(provider.getHost(), provider.getServer(), stepLabels);
//            }
            result = domainOperationContext.getServerResult(provider.getHost(), provider.getServer(), stepLabels);
        }

        return result == null ? new ModelNode() : result;
    }

    private ModelNode getHostControllerResult(final ModelNode fullResult, final String... stepLabels) {
        ModelNode result = null;
        if (fullResult != null && fullResult.hasDefined(RESULT) && fullResult.get(RESULT).hasDefined(DOMAIN_RESULTS)) {
            ModelNode domainResults = fullResult.get(RESULT, DOMAIN_RESULTS);
            result = domainResults.get(stepLabels);
            if (result.has(OUTCOME) && !result.hasDefined(OUTCOME)) {
                if (result.hasDefined(FAILURE_DESCRIPTION)) {
                    result.get(OUTCOME).set(FAILED);
                } else {
                    result.get(OUTCOME).set(SUCCESS);
                }
            }
        }
        return result;
    }

    private void populateServerGroupResults(final OperationContext context, final Map<ServerIdentity, ModelNode> serverResults) {

        final Set<String> groupNames = new TreeSet<String>();
        final Map<String, Set<HostServer>> groupToServerMap = new HashMap<String, Set<HostServer>>();
        for (Map.Entry<ServerIdentity, ModelNode> entry : serverResults.entrySet()) {
            final String serverGroup = entry.getKey().getServerGroupName();
            groupNames.add(serverGroup);
            final String hostName = entry.getKey().getHostName();
            final String serverName = entry.getKey().getServerName();
            if (!groupToServerMap.containsKey(serverGroup)) {
                groupToServerMap.put(serverGroup, new TreeSet<HostServer>());
            }
            groupToServerMap.get(serverGroup).add(new HostServer(hostName, serverName, entry.getValue()));
        }

        boolean serverGroupSuccess = false;
        ModelNode failureReport = new ModelNode();
        for (String groupName : groupNames) {
            final ModelNode groupNode = new ModelNode();
            boolean groupFailure = domainOperationContext.isServerGroupRollback(groupName);
            if (groupFailure) {
                // TODO revisit if we should report this for the whole group, since the result might not be accurate
                // groupNode.get(ROLLED_BACK).set(true);
            } else {
                serverGroupSuccess = true;
            }
            for (HostServer hostServer : groupToServerMap.get(groupName)) {
                groupNode.get(HOST, hostServer.hostName, hostServer.serverName, RESPONSE).set(hostServer.result);
                if (groupFailure && hostServer.result.hasDefined(OUTCOME)
                        && FAILED.equals(hostServer.result.get(OUTCOME).asString())
                        && hostServer.result.hasDefined(FAILURE_DESCRIPTION)) {
                    ModelNode failDesc = hostServer.result.get(FAILURE_DESCRIPTION);
                    if (!CompositeOperationHandler.getUnexplainedFailureMessage().equals(failDesc.asString())) {
                        failureReport.get(SERVER_GROUP, groupName, HOST, hostServer.hostName, hostServer.serverName).set(failDesc);
                    } // else the server just reported an unexplained composite failure.
                      // We assume it's due to domain-wide rollback, which is not useful information
                      // so don't include this server in the top level failure description
                }
            }
            context.getServerResults().get(groupName).set(groupNode);
        }
        if (!serverGroupSuccess) {
            if (failureReport.isDefined()) {
                ModelNode fullFailure = new ModelNode();
                fullFailure.get(DomainControllerLogger.ROOT_LOGGER.operationFailedOrRolledBackWithCause()).set(failureReport);
                context.getFailureDescription().set(fullFailure);
            } else {
                context.getFailureDescription().set(DomainControllerLogger.ROOT_LOGGER.operationFailedOrRolledBack());
            }
        }
    }

    private boolean isDomainOperation(final ModelNode operation) {
        final PathAddress address = PathAddress.pathAddress(operation.require(OP_ADDR));
        return address.size() == 0 || !address.getElement(0).getKey().equals(HOST);
    }

    private static class HostServer implements Comparable<HostServer> {
        private final String hostName;
        private final String serverName;
        private final ModelNode result;

        private HostServer(String hostName, String serverName, ModelNode result) {
            this.hostName = hostName;
            this.serverName = serverName;
            this.result = result;
        }

        public int compareTo(HostServer hostServer) {
            int hostCompare = hostName.compareTo(hostServer.hostName);
            if (hostCompare != 0) {
                return hostCompare;
            }
            return serverName.compareTo(hostServer.serverName);
        }
    }

    private static class ResponseProvider {
        private final String host;
        private final String server;
        private final List<ModelNode> children;

        private ResponseProvider(final ModelNode operation, final String localHostName) {

            boolean composite = COMPOSITE.equals(operation.require(OP).asString());
            PathAddress opAddr = PathAddress.pathAddress(operation.get(OP_ADDR));
            int addrSize = opAddr.size();
            if (addrSize == 0) {
                host = localHostName;
                server = null;
            } else if (HOST.equals(opAddr.getElement(0).getKey()) && !opAddr.getElement(0).isMultiTarget()) {
                host = opAddr.getElement(0).getValue();
                if (addrSize > 1 && SERVER.equals(opAddr.getElement(1).getKey())
                        && !opAddr.getElement(1).isMultiTarget()) {
                    server =  opAddr.getElement(1).getValue();
                    composite = composite && addrSize == 2;
                } else {
                    server = null;
                }
            } else {
                // A domain op
                host = localHostName;
                server = null;
                composite = false;
            }

            if (composite) {
                if (operation.hasDefined(STEPS)) {
                    children = new ArrayList<ModelNode>(operation.require(STEPS).asList());
                } else {
                    // This shouldn't be possible
                    children = Collections.emptyList();
                }
            } else {
                children = null;
            }
        }

        private String getHost() {
            return host;
        }

        private String getServer() {
            return server;
        }

        private List<ModelNode> getChildren() {
            return children;
        }

        private boolean isLeaf() {
            return children == null;
        }
    }
}
