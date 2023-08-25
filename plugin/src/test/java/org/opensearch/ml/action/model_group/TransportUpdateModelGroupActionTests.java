/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.model_group;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.opensearch.action.get.GetResponse;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.update.UpdateResponse;
import org.opensearch.client.Client;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.commons.ConfigConstants;
import org.opensearch.commons.authuser.User;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.index.get.GetResult;
import org.opensearch.ml.common.MLModelGroup;
import org.opensearch.ml.common.ModelAccessMode;
import org.opensearch.ml.common.exception.MLException;
import org.opensearch.ml.common.exception.MLResourceNotFoundException;
import org.opensearch.ml.common.transport.model_group.MLUpdateModelGroupInput;
import org.opensearch.ml.common.transport.model_group.MLUpdateModelGroupRequest;
import org.opensearch.ml.common.transport.model_group.MLUpdateModelGroupResponse;
import org.opensearch.ml.helper.ModelAccessControlHelper;
import org.opensearch.tasks.Task;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

public class TransportUpdateModelGroupActionTests extends OpenSearchTestCase {
    @Rule
    public ExpectedException exceptionRule = ExpectedException.none();
    private String indexName = "testIndex";

    @Mock
    private TransportService transportService;

    @Mock
    private ClusterService clusterService;

    @Mock
    private ThreadPool threadPool;

    @Mock
    private Task task;

    @Mock
    private Client client;
    @Mock
    private ActionFilters actionFilters;

    @Mock
    private NamedXContentRegistry xContentRegistry;

    @Mock
    private ActionListener<MLUpdateModelGroupResponse> actionListener;

    @Mock
    private UpdateResponse updateResponse;

    ThreadContext threadContext;

    private TransportUpdateModelGroupAction transportUpdateModelGroupAction;

    @Mock
    private ModelAccessControlHelper modelAccessControlHelper;

    private String ownerString = "bob|IT,HR|myTenant";
    private List<String> backendRoles = Arrays.asList("IT");

    @Before
    public void setup() throws IOException {
        MockitoAnnotations.openMocks(this);
        Settings settings = Settings.builder().build();
        threadContext = new ThreadContext(settings);
        transportUpdateModelGroupAction = new TransportUpdateModelGroupAction(
            transportService,
            actionFilters,
            client,
            xContentRegistry,
            clusterService,
            modelAccessControlHelper
        );
        assertNotNull(transportUpdateModelGroupAction);

        doAnswer(invocation -> {
            ActionListener<UpdateResponse> listener = invocation.getArgument(1);
            listener.onResponse(updateResponse);
            return null;
        }).when(client).update(any(), any());

        MLModelGroup mlModelGroup = MLModelGroup
            .builder()
            .modelGroupId("testModelGroupId")
            .name("testModelGroup")
            .description("This is test model Group")
            .owner(User.parse(ownerString))
            .backendRoles(backendRoles)
            .access("restricted")
            .build();
        XContentBuilder content = mlModelGroup.toXContent(XContentFactory.jsonBuilder(), ToXContent.EMPTY_PARAMS);
        BytesReference bytesReference = BytesReference.bytes(content);
        GetResult getResult = new GetResult(indexName, "111", 111l, 111l, 111l, true, bytesReference, null, null);
        GetResponse getResponse = new GetResponse(getResult);
        doAnswer(invocation -> {
            ActionListener<GetResponse> actionListener = invocation.getArgument(1);
            actionListener.onResponse(getResponse);
            return null;
        }).when(client).get(any(), any());

        when(modelAccessControlHelper.isSecurityEnabledAndModelAccessControlEnabled(any())).thenReturn(true);
        when(client.threadPool()).thenReturn(threadPool);
        when(threadPool.getThreadContext()).thenReturn(threadContext);
    }

    public void test_NonOwnerChangingAccessContentException() {
        when(modelAccessControlHelper.isOwner(any(), any())).thenReturn(false);
        when(modelAccessControlHelper.isAdmin(any())).thenReturn(false);

        MLUpdateModelGroupRequest actionRequest = prepareRequest(null, ModelAccessMode.RESTRICTED, true);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals("Only owner/admin has valid privilege to perform update access control data", argumentCaptor.getValue().getMessage());
    }

    public void test_OwnerNoMoreHasPermissionException() {
        when(modelAccessControlHelper.isOwner(any(), any())).thenReturn(true);
        when(modelAccessControlHelper.isOwnerStillHasPermission(any(), any())).thenReturn(false);

        MLUpdateModelGroupRequest actionRequest = prepareRequest(null, ModelAccessMode.RESTRICTED, true);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals(
            "Owner doesn't have corresponding backend role to perform update access control data, please check with admin user",
            argumentCaptor.getValue().getMessage()
        );
    }

    public void test_NonOwnerUpdatingPrivateModelGroupException() {
        when(modelAccessControlHelper.isOwner(any(), any())).thenReturn(false);
        when(modelAccessControlHelper.isAdmin(any())).thenReturn(false);
        when(modelAccessControlHelper.isUserHasBackendRole(any(), any())).thenReturn(false);

        MLUpdateModelGroupRequest actionRequest = prepareRequest(null, null, null);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals("User doesn't have corresponding backend role to perform update action", argumentCaptor.getValue().getMessage());
    }

    public void test_BackendRolesProvidedWithPrivate() {
        when(modelAccessControlHelper.isOwner(any(), any())).thenReturn(true);
        when(modelAccessControlHelper.isUserHasBackendRole(any(), any())).thenReturn(true);
        when(modelAccessControlHelper.isAdmin(any())).thenReturn(false);
        when(modelAccessControlHelper.isOwnerStillHasPermission(any(), any())).thenReturn(true);

        MLUpdateModelGroupRequest actionRequest = prepareRequest(null, ModelAccessMode.PRIVATE, true);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals("User cannot specify backend roles to a public/private model group", argumentCaptor.getValue().getMessage());
    }

    public void test_BackendRolesProvidedWithPublic() {
        when(modelAccessControlHelper.isOwner(any(), any())).thenReturn(true);
        when(modelAccessControlHelper.isUserHasBackendRole(any(), any())).thenReturn(true);
        when(modelAccessControlHelper.isAdmin(any())).thenReturn(false);
        when(modelAccessControlHelper.isOwnerStillHasPermission(any(), any())).thenReturn(true);

        MLUpdateModelGroupRequest actionRequest = prepareRequest(null, ModelAccessMode.PUBLIC, true);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals("User cannot specify backend roles to a public/private model group", argumentCaptor.getValue().getMessage());
    }

    public void test_AdminSpecifiedAddAllBackendRolesForRestricted() {
        when(modelAccessControlHelper.isOwner(any(), any())).thenReturn(false);
        when(modelAccessControlHelper.isAdmin(any())).thenReturn(true);

        MLUpdateModelGroupRequest actionRequest = prepareRequest(null, ModelAccessMode.RESTRICTED, true);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals("Admin user cannot specify add all backend roles to a model group", argumentCaptor.getValue().getMessage());
    }

    public void test_UserWithNoBackendRolesSpecifiedRestricted() {
        threadContext.putTransient(ConfigConstants.OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT, "bob||engineering,operations");
        when(modelAccessControlHelper.isOwner(any(), any())).thenReturn(true);
        when(modelAccessControlHelper.isOwnerStillHasPermission(any(), any())).thenReturn(true);
        when(modelAccessControlHelper.isAdmin(any())).thenReturn(false);

        MLUpdateModelGroupRequest actionRequest = prepareRequest(null, ModelAccessMode.RESTRICTED, true);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals("Current user doesn't have any backend role", argumentCaptor.getValue().getMessage());
    }

    public void test_UserSpecifiedRestrictedButNoBackendRolesFieldF() {
        when(modelAccessControlHelper.isSecurityEnabledAndModelAccessControlEnabled(any())).thenReturn(true);
        when(modelAccessControlHelper.isOwner(any(), any())).thenReturn(true);
        when(modelAccessControlHelper.isOwnerStillHasPermission(any(), any())).thenReturn(true);
        when(modelAccessControlHelper.isAdmin(any())).thenReturn(false);

        MLUpdateModelGroupRequest actionRequest = prepareRequest(null, ModelAccessMode.RESTRICTED, false);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals("User have to specify backend roles when add all backend roles to false", argumentCaptor.getValue().getMessage());
    }

    public void test_RestrictedAndUserSpecifiedBothBackendRolesFields() {
        threadContext.putTransient(ConfigConstants.OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT, "alex|IT,HR|engineering,operations");
        when(modelAccessControlHelper.isOwner(any(), any())).thenReturn(true);
        when(modelAccessControlHelper.isOwnerStillHasPermission(any(), any())).thenReturn(true);
        when(modelAccessControlHelper.isAdmin(any())).thenReturn(false);

        MLUpdateModelGroupRequest actionRequest = prepareRequest(backendRoles, ModelAccessMode.RESTRICTED, true);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals(
            "User cannot specify add all backed roles to true and backend roles not empty",
            argumentCaptor.getValue().getMessage()
        );
    }

    public void test_RestrictedAndUserSpecifiedIncorrectBackendRoles() {
        threadContext.putTransient(ConfigConstants.OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT, "alex|IT,HR|engineering,operations");
        when(modelAccessControlHelper.isOwner(any(), any())).thenReturn(true);
        when(modelAccessControlHelper.isOwnerStillHasPermission(any(), any())).thenReturn(true);
        when(modelAccessControlHelper.isAdmin(any())).thenReturn(false);

        List<String> incorrectBackendRole = Arrays.asList("Finance");

        MLUpdateModelGroupRequest actionRequest = prepareRequest(incorrectBackendRole, ModelAccessMode.RESTRICTED, null);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals("User cannot specify backend roles that doesn't belong to the current user", argumentCaptor.getValue().getMessage());
    }

    public void test_SuccessPrivateWithOwnerAsUser() {
        when(modelAccessControlHelper.isOwner(any(), any())).thenReturn(true);
        when(modelAccessControlHelper.isOwnerStillHasPermission(any(), any())).thenReturn(true);
        when(modelAccessControlHelper.isAdmin(any())).thenReturn(false);

        MLUpdateModelGroupRequest actionRequest = prepareRequest(null, ModelAccessMode.PRIVATE, null);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<MLUpdateModelGroupResponse> argumentCaptor = ArgumentCaptor.forClass(MLUpdateModelGroupResponse.class);
        verify(actionListener).onResponse(argumentCaptor.capture());
    }

    public void test_SuccessRestricedWithOwnerAsUser() {
        threadContext.putTransient(ConfigConstants.OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT, "bob|IT,HR|myTenant");
        when(modelAccessControlHelper.isOwner(any(), any())).thenReturn(true);
        when(modelAccessControlHelper.isOwnerStillHasPermission(any(), any())).thenReturn(true);
        when(modelAccessControlHelper.isAdmin(any())).thenReturn(false);

        MLUpdateModelGroupRequest actionRequest = prepareRequest(null, ModelAccessMode.RESTRICTED, true);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<MLUpdateModelGroupResponse> argumentCaptor = ArgumentCaptor.forClass(MLUpdateModelGroupResponse.class);
        verify(actionListener).onResponse(argumentCaptor.capture());
    }

    public void test_SuccessPublicWithAdminAsUser() {
        when(modelAccessControlHelper.isOwner(any(), any())).thenReturn(true);
        when(modelAccessControlHelper.isOwnerStillHasPermission(any(), any())).thenReturn(true);
        when(modelAccessControlHelper.isAdmin(any())).thenReturn(true);

        MLUpdateModelGroupRequest actionRequest = prepareRequest(null, ModelAccessMode.PUBLIC, null);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<MLUpdateModelGroupResponse> argumentCaptor = ArgumentCaptor.forClass(MLUpdateModelGroupResponse.class);
        verify(actionListener).onResponse(argumentCaptor.capture());
    }

    public void test_SuccessRestrictedWithAdminAsUser() {
        when(modelAccessControlHelper.isOwner(any(), any())).thenReturn(false);
        when(modelAccessControlHelper.isAdmin(any())).thenReturn(true);

        MLUpdateModelGroupRequest actionRequest = prepareRequest(backendRoles, ModelAccessMode.RESTRICTED, null);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<MLUpdateModelGroupResponse> argumentCaptor = ArgumentCaptor.forClass(MLUpdateModelGroupResponse.class);
        verify(actionListener).onResponse(argumentCaptor.capture());
    }

    public void test_SuccessNonOwnerUpdatingWithNoAccessContent() {
        when(modelAccessControlHelper.isSecurityEnabledAndModelAccessControlEnabled(any())).thenReturn(true);
        when(modelAccessControlHelper.isOwner(any(), any())).thenReturn(false);
        when(modelAccessControlHelper.isAdmin(any())).thenReturn(false);
        when(modelAccessControlHelper.isUserHasBackendRole(any(), any())).thenReturn(true);

        MLUpdateModelGroupRequest actionRequest = prepareRequest(null, null, null);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<MLUpdateModelGroupResponse> argumentCaptor = ArgumentCaptor.forClass(MLUpdateModelGroupResponse.class);
        verify(actionListener).onResponse(argumentCaptor.capture());
    }

    public void test_FailedToFindModelGroupException() {
        doAnswer(invocation -> {
            ActionListener<GetResponse> actionListener = invocation.getArgument(1);
            actionListener.onFailure(new MLResourceNotFoundException("Failed to find model group"));
            return null;
        }).when(client).get(any(), any());

        MLUpdateModelGroupRequest actionRequest = prepareRequest(null, ModelAccessMode.RESTRICTED, null);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals("Failed to find model group", argumentCaptor.getValue().getMessage());
    }

    public void test_FailedToGetModelGroupException() {
        doAnswer(invocation -> {
            ActionListener<GetResponse> actionListener = invocation.getArgument(1);
            actionListener.onFailure(new Exception("Failed to get model group"));
            return null;
        }).when(client).get(any(), any());

        MLUpdateModelGroupRequest actionRequest = prepareRequest(null, ModelAccessMode.RESTRICTED, null);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals("Failed to get model group", argumentCaptor.getValue().getMessage());
    }

    public void test_FailedToUpdatetModelGroupException() {
        doAnswer(invocation -> {
            ActionListener<UpdateResponse> listener = invocation.getArgument(1);
            listener.onFailure(new MLException("Failed to update Model Group"));
            return null;
        }).when(client).update(any(), any());

        when(modelAccessControlHelper.isAdmin(any())).thenReturn(true);

        MLUpdateModelGroupRequest actionRequest = prepareRequest(null, ModelAccessMode.PUBLIC, null);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals("Failed to update Model Group", argumentCaptor.getValue().getMessage());
    }

    public void test_SuccessSecurityDisabledCluster() {
        when(modelAccessControlHelper.isSecurityEnabledAndModelAccessControlEnabled(any())).thenReturn(false);

        MLUpdateModelGroupRequest actionRequest = prepareRequest(null, null, null);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<MLUpdateModelGroupResponse> argumentCaptor = ArgumentCaptor.forClass(MLUpdateModelGroupResponse.class);
        verify(actionListener).onResponse(argumentCaptor.capture());
    }

    @Ignore
    public void test_ExceptionSecurityDisabledCluster() {
        when(modelAccessControlHelper.isSecurityEnabledAndModelAccessControlEnabled(any())).thenReturn(false);

        MLUpdateModelGroupRequest actionRequest = prepareRequest(null, null, true);
        transportUpdateModelGroupAction.doExecute(task, actionRequest, actionListener);
        ArgumentCaptor<Exception> argumentCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(actionListener).onFailure(argumentCaptor.capture());
        assertEquals(
            "Cluster security plugin not enabled or model access control not enabled, can't pass access control data in request body",
            argumentCaptor.getValue().getMessage()
        );
    }

    private MLUpdateModelGroupRequest prepareRequest(
        List<String> backendRoles,
        ModelAccessMode modelAccessMode,
        Boolean isAddAllBackendRoles
    ) {
        MLUpdateModelGroupInput UpdateModelGroupInput = MLUpdateModelGroupInput
            .builder()
            .modelGroupID("testModelGroupId")
            .name("modelGroupName")
            .description("This is a test model group")
            .backendRoles(backendRoles)
            .modelAccessMode(modelAccessMode)
            .isAddAllBackendRoles(isAddAllBackendRoles)
            .build();
        return new MLUpdateModelGroupRequest(UpdateModelGroupInput);
    }

}