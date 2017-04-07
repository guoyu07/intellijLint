package com.lmax.intellijLint.Units;

import com.intellij.codeInspection.BaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.util.SpecialAnnotationsUtil;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.psi.*;
import com.siyeh.ig.ui.ExternalizableStringSet;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;

@SuppressWarnings("WeakerAccess") //Needs to be public as is used in plugin.
@State(name = "unitsInspection", storages = {@Storage("com.lmax.intellijLint.units.xml")})
public class UnitsInspection extends BaseJavaLocalInspectionTool implements PersistentStateComponent<UnitsInspection.State>, com.intellij.openapi.components.ProjectComponent
{
    @Nls
    @NotNull
    @Override
    public String getDisplayName() {
        return "Mismatched units";
    }

    @Nls
    @NotNull
    @Override
    public String getGroupDisplayName() {
        return "LMAX";
    }

    public static final String DESCRIPTION_TEMPLATE = "Assigning %s to variable of type %s";
    public static final String ARGUMENT_TEMPLATE = "Passing %s when expecting a parameter of type %s";
    public static final String BINARY_EXPRESSION_DESCRIPTION_TEMPLATE = "Left side of expression is %s and right side is %s";
    public static final String RETURNING_DESCRIPTION_TEMPLATE = "Returning %s when expecting %s";
    public static final String FAILED_TO_RESOLVE = "Failed to resolve subtype on %s due to %s";
    public static final String POLYADIC_MISMATCH = "Found %s when rest of expression is %s";

    @SuppressWarnings("PublicField")
    public final List<String> subTypeAnnotations = new ArrayList<>();

    private PsiMethod walkUpToWrappingMethod(PsiElement element) {
        if (element == null) {
            return null;
        }

        PsiElement parent = element.getParent();
        if (parent == null) {
            return null;
        }

        if (parent instanceof PsiMethod) {
            return (PsiMethod) parent;
        } else {
            return walkUpToWrappingMethod(parent);
        }
    }

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAssignmentExpression(PsiAssignmentExpression expression) {
                super.visitAssignmentExpression(expression);

                final PsiExpression initalizerExpr = expression.getRExpression();
                if (initalizerExpr == null) {
                    return;
                }

                SubType declared = SubType.getSubType(expression.getLExpression());
                if (!declared.isResolved()) {
                    reportResolutionFailure(declared, holder);
                    return;
                }

                if (declared.hasSubtype()) {
                    SubType assigned = SubType.getSubType(initalizerExpr);
                    inspect(assigned, declared, holder);
                }
            }

            @Override
            public void visitField(PsiField field) {
                super.visitField(field);
                final SubType declared = SubType.getSubType(field);
                if (!declared.hasSubtype()) {
                    return;
                }

                final PsiExpression initializerExpr = field.getInitializer();
                if (initializerExpr == null) {
                    return;
                }

                final SubType initializer = SubType.getSubType(initializerExpr);
                inspect(initializer, declared, holder);
            }

            @Override
            public void visitLocalVariable(PsiLocalVariable variable) {
                super.visitLocalVariable(variable);

                final SubType declared = SubType.getSubType(variable);
                if (!declared.hasSubtype()) {
                    return;
                }

                final PsiExpression initializerExpression = variable.getInitializer();

                if (initializerExpression == null) {
                    return;
                }

                final SubType initializer = SubType.getSubType(initializerExpression);
                inspect(initializer, declared, holder);
            }

            @Override
            public void visitReturnStatement(PsiReturnStatement statement) {
                super.visitReturnStatement(statement);

                final PsiExpression returnValueExpr = statement.getReturnValue();

                if (returnValueExpr == null) {
                    return; // void return, won't have annotation.
                }

                PsiMethod psiMethod = walkUpToWrappingMethod(returnValueExpr);
                final SubType declared = SubType.getSubType(psiMethod);

                if (!declared.isResolved()) {
                    reportResolutionFailure(declared, holder);
                    return;
                }

                if (declared.hasSubtype()) {
                    final SubType returnValue = SubType.getSubType(returnValueExpr);
                    inspect(returnValue, declared, holder, RETURNING_DESCRIPTION_TEMPLATE);
                }
            }

            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                super.visitMethodCallExpression(expression);

                final PsiMethod psiMethod = expression.resolveMethod();

                if (psiMethod == null) {
                    //TODO: Might be a lambda. Deal with that somehow.
                    reportResolutionFailure(expression, "being unable to resolve method", holder);
                    return;
                }

                final PsiParameter[] parameters = psiMethod.getParameterList().getParameters();
                final PsiExpression[] argExprs = expression.getArgumentList().getExpressions();

                for (int i = 0; i < parameters.length; i++) {
                    final SubType paramSubType = SubType.getSubType(parameters[i]);
                    final SubType argSubType = SubType.getSubType(argExprs[i]);

                    inspect(argSubType, paramSubType, holder, ARGUMENT_TEMPLATE);
                }
            }
        };
    }

    private void inspect(SubType potentiallyProblematic, SubType checkAgainst, @NotNull ProblemsHolder holder) {
        inspect(potentiallyProblematic, checkAgainst, holder, DESCRIPTION_TEMPLATE);
    }

    private void inspect(SubType potentiallyProblematic, SubType checkAgainst, @NotNull ProblemsHolder holder, String descriptionTemplate) {
        inspect(potentiallyProblematic.getPsiElement(), potentiallyProblematic, checkAgainst, holder, descriptionTemplate);
    }

    private void inspect(PsiElement element, SubType left, SubType right, @NotNull ProblemsHolder holder, String descriptionTemplate) {
        if (reportResolutionFailure(left, holder) || reportResolutionFailure(right, holder)) {
            return;
        }

        if (!Objects.equals(left, right)) {
            final String description = String.format(descriptionTemplate, left.getSubtypeFQN(), right.getSubtypeFQN());
            holder.registerProblem(element, description);
        }
    }

    private boolean reportResolutionFailure(SubType subType, @NotNull ProblemsHolder holder) {
        if (subType.getFailureReason() != ResolutionFailureReason.NONE && !isIgnoredResolutionFailureReason(subType)) {
            reportResolutionFailure(subType.getPsiElement(), subType.getFailureReason().toString(), holder);
            return true;
        }
        return false;
    }

    private void reportResolutionFailure(PsiElement element, String failureReason, @NotNull ProblemsHolder holder) {
        final String description = String.format(FAILED_TO_RESOLVE, element, failureReason);
        holder.registerProblem(element, description);
    }

    private boolean isIgnoredResolutionFailureReason(SubType subType) {
        final ResolutionFailureReason faliureReason = subType.getFailureReason();
        return faliureReason == ResolutionFailureReason.MISMATCHED_CONDITIONAL ||
                faliureReason == ResolutionFailureReason.MISMATCHED_BINARY_EXPRESSION;
    }


    public JComponent createOptionsPanel() {
        return SpecialAnnotationsUtil.createSpecialAnnotationsListControl(
                this.subTypeAnnotations, "Sub Type annotations");
    }

    public boolean isEnabledByDefault() {
        return true;
    }

    @Nullable
    @Override
    public UnitsInspection.State getState() {
        State state = new State();
        state.subTypeAnnotations = new HashSet<>(this.subTypeAnnotations);
        return state;
    }

    @Override
    public void loadState(UnitsInspection.State state) {
        SubType.setAnnotations(state.subTypeAnnotations);
        this.subTypeAnnotations.clear();
        this.subTypeAnnotations.addAll(state.subTypeAnnotations);
    }

    @Override
    public void projectOpened() {

    }

    @Override
    public void projectClosed() {

    }

    @Override
    public void initComponent() {

    }

    @Override
    public void disposeComponent() {

    }

    @NotNull
    @Override
    public String getComponentName() {
        return "UnitsInspection";
    }

    public static class State {
        public State() {
            subTypeAnnotations = new ExternalizableStringSet("org.checkerframework.framework.qual.SubtypeOf");
        }

        public Set<String> subTypeAnnotations;
    }
}
