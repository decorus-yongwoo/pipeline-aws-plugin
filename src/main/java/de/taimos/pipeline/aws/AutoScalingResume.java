package de.taimos.pipeline.aws;

import com.amazonaws.services.autoscaling.AmazonAutoScaling;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClientBuilder;
import com.amazonaws.services.autoscaling.model.ResourceContentionException;
import com.amazonaws.services.autoscaling.model.ResourceInUseException;
import com.amazonaws.services.autoscaling.model.ResumeProcessesRequest;
import de.taimos.pipeline.aws.utils.StepUtils;
import hudson.Extension;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.workflow.steps.Step;

import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.util.Set;

public class AutoScalingResume extends Step {
    private String groupName;

    @DataBoundConstructor
    public AutoScalingResume(String groupName) {
        this.groupName = groupName;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new AutoScalingResume.Execution(groupName, context);
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return StepUtils.requiresDefault();
        }

        @Override
        public String getFunctionName() {
            return "autoScalingResume";
        }

        @Override
        @Nonnull
        public String getDisplayName() {
            return "Resume autoscaling";
        }
    }

    public static class Execution extends SynchronousNonBlockingStepExecution<Void> {
        private final String groupName;
        private final transient AmazonAutoScaling client;

        public Execution(String groupName, StepContext context) {
            super(context);
            this.groupName = groupName;
            this.client = AWSClientFactory.create(AmazonAutoScalingClientBuilder.standard(), this.getContext());
        }

        /**
         * @return Amazon AutoScaling Resume process request instance
         */
        private ResumeProcessesRequest createResumeProcessesRequest() throws Exception {
            return new ResumeProcessesRequest()
                    .withAutoScalingGroupName(this.groupName);
        }

        @Override
        protected Void run() throws Exception {
            TaskListener listener = this.getContext().get(TaskListener.class);
            listener.getLogger().println("Resume AutoScaling");
            ResumeProcessesRequest request = this.createResumeProcessesRequest();
            try {
                this.client.resumeProcesses( request );
            } catch ( ResourceInUseException | ResourceContentionException exception) {
                listener.getLogger().println("Failed resume auto scaling");
                listener.getLogger().println(exception.getMessage());
                exception.printStackTrace(listener.getLogger());
            } catch ( Exception exception ){
                listener.getLogger().println("Unknown autoscaling error");
                exception.printStackTrace(listener.getLogger());
            }
            return null;
        }
    }
}
