/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */
package com.microsoft.jenkins.appservice;

import com.microsoft.azure.management.appservice.DeploymentSlot;
import com.microsoft.azure.management.appservice.JavaVersion;
import com.microsoft.azure.management.appservice.PublishingProfile;
import com.microsoft.azure.management.appservice.WebApp;
import com.microsoft.jenkins.appservice.commands.DefaultDockerClientBuilder;
import com.microsoft.jenkins.appservice.commands.DockerBuildCommand;
import com.microsoft.jenkins.appservice.commands.DockerBuildInfo;
import com.microsoft.jenkins.appservice.commands.DockerClientBuilder;
import com.microsoft.jenkins.appservice.commands.DockerDeployCommand;
import com.microsoft.jenkins.appservice.commands.DockerPushCommand;
import com.microsoft.jenkins.appservice.commands.DockerRemoveImageCommand;
import com.microsoft.jenkins.appservice.commands.FTPDeployCommand;
import com.microsoft.jenkins.appservice.commands.GitDeployCommand;
import com.microsoft.jenkins.azurecommons.command.BaseCommandContext;
import com.microsoft.jenkins.azurecommons.command.CommandState;
import com.microsoft.jenkins.azurecommons.command.IBaseCommandData;
import com.microsoft.jenkins.azurecommons.command.ICommand;
import com.microsoft.jenkins.azurecommons.command.TransitionInfo;
import com.microsoft.jenkins.exceptions.AzureCloudException;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

import java.util.HashMap;

public class WebAppDeploymentCommandContext extends BaseCommandContext
        implements FTPDeployCommand.IFTPDeployCommandData,
        GitDeployCommand.IGitDeployCommandData,
        DockerBuildCommand.IDockerBuildCommandData,
        DockerPushCommand.IDockerPushCommandData,
        DockerRemoveImageCommand.IDockerRemoveImageCommandData,
        DockerDeployCommand.IDockerDeployCommandData {

    public static final String PUBLISH_TYPE_DOCKER = "docker";

    private final String filePath;
    private String publishType;
    private DockerBuildInfo dockerBuildInfo;
    private String sourceDirectory;
    private String targetDirectory;
    private String slotName;
    private boolean deleteTempImage;
    private String azureCredentialsId;

    private PublishingProfile pubProfile;
    private WebApp webApp;

    public WebAppDeploymentCommandContext(final String filePath) {
        this.filePath = filePath;
        this.sourceDirectory = "";
        this.targetDirectory = "";
    }

    public void setSourceDirectory(final String sourceDirectory) {
        this.sourceDirectory = Util.fixNull(sourceDirectory);
    }

    public void setTargetDirectory(final String targetDirectory) {
        this.targetDirectory = Util.fixNull(targetDirectory);
    }

    public void setSlotName(final String slotName) {
        this.slotName = slotName;
    }

    public void setPublishType(final String publishType) {
        this.publishType = publishType;
    }

    public void setDockerBuildInfo(final DockerBuildInfo dockerBuildInfo) {
        this.dockerBuildInfo = dockerBuildInfo;
    }

    public void setDeleteTempImage(final boolean deleteTempImage) {
        this.deleteTempImage = deleteTempImage;
    }

    public void setAzureCredentialsId(final String azureCredentialsId) {
        this.azureCredentialsId = azureCredentialsId;
    }

    public void configure(
            final Run<?, ?> run,
            final FilePath workspace,
            final Launcher launcher,
            final TaskListener listener,
            final WebApp app) throws AzureCloudException {
        if (StringUtils.isBlank(slotName)) {
            // Deploy to default
            pubProfile = app.getPublishingProfile();
        } else {
            // Deploy to slot
            final DeploymentSlot slot = app.deploymentSlots().getByName(slotName);
            if (slot == null) {
                throw new AzureCloudException(String.format("Slot %s not found", slotName));
            }

            pubProfile = slot.getPublishingProfile();
        }

        HashMap<Class, TransitionInfo> commands = new HashMap<>();

        Class startCommandClass;
        if (StringUtils.isNotBlank(publishType) && publishType.equalsIgnoreCase(PUBLISH_TYPE_DOCKER)) {
            startCommandClass = DockerBuildCommand.class;
            this.webApp = app;
            commands.put(DockerBuildCommand.class, new TransitionInfo(
                    new DockerBuildCommand(), DockerPushCommand.class, null));
            commands.put(DockerPushCommand.class, new TransitionInfo(
                    new DockerPushCommand(), DockerDeployCommand.class, null));
            if (deleteTempImage) {
                commands.put(DockerDeployCommand.class, new TransitionInfo(
                        new DockerDeployCommand(), DockerRemoveImageCommand.class, null));
                commands.put(DockerRemoveImageCommand.class, new TransitionInfo(
                        new DockerRemoveImageCommand(), null, null));
            } else {
                commands.put(DockerDeployCommand.class, new TransitionInfo(
                        new DockerDeployCommand(), null, null));
            }
        } else if (app.javaVersion() != JavaVersion.OFF) {
            // For Java application, use FTP-based deployment as it's the recommended way
            startCommandClass = FTPDeployCommand.class;
            commands.put(FTPDeployCommand.class, new TransitionInfo(
                    new FTPDeployCommand(), null, null));
        } else {
            // For non-Java application, use Git-based deployment
            startCommandClass = GitDeployCommand.class;
            commands.put(GitDeployCommand.class, new TransitionInfo(
                    new GitDeployCommand(), null, null));
        }

        super.configure(run, workspace, launcher, listener, commands, startCommandClass);
        this.setCommandState(CommandState.Running);
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return null;
    }

    @Override
    public IBaseCommandData getDataForCommand(final ICommand command) {
        return this;
    }

    @Override
    public String getFilePath() {
        return filePath;
    }

    @Override
    public String getSourceDirectory() {
        return sourceDirectory;
    }

    @Override
    public String getTargetDirectory() {
        return targetDirectory;
    }

    public String getPublishType() {
        return publishType;
    }

    @Override
    public PublishingProfile getPublishingProfile() {
        return pubProfile;
    }

    @Override
    public DockerBuildInfo getDockerBuildInfo() {
        return dockerBuildInfo;
    }

    @Override
    public DockerClientBuilder getDockerClientBuilder() {
        return new DefaultDockerClientBuilder();
    }

    @Override
    public WebApp getWebApp() {
        return webApp;
    }

    @Override
    public String getSlotName() {
        return this.slotName;
    }

    @Override
    public String getAzureCredentialsId() {
        return this.azureCredentialsId;
    }
}
