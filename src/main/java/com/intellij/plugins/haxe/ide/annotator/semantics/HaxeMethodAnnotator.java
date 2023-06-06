package com.intellij.plugins.haxe.ide.annotator.semantics;

import com.intellij.lang.annotation.*;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.HaxeLanguage;
import com.intellij.plugins.haxe.ide.annotator.HaxeSemanticAnnotatorConfig;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.model.*;
import com.intellij.plugins.haxe.model.fixer.HaxeFixer;
import com.intellij.plugins.haxe.model.fixer.HaxeModifierAddFixer;
import com.intellij.plugins.haxe.model.fixer.HaxeModifierRemoveFixer;
import com.intellij.plugins.haxe.model.fixer.HaxeModifierReplaceVisibilityFixer;
import com.intellij.plugins.haxe.model.type.*;
import com.intellij.plugins.haxe.util.HaxeResolveUtil;
import com.intellij.psi.PsiElement;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;

import static com.intellij.plugins.haxe.ide.annotator.HaxeSemanticAnnotatorInspections.*;
import static com.intellij.plugins.haxe.ide.annotator.HaxeStandardAnnotation.returnTypeMismatch;
import static com.intellij.plugins.haxe.ide.annotator.HaxeStandardAnnotation.typeMismatch;
import static com.intellij.plugins.haxe.lang.psi.HaxePsiModifier.*;
import static com.intellij.plugins.haxe.lang.psi.HaxePsiModifier.OVERRIDE;
import static com.intellij.plugins.haxe.model.type.HaxeTypeCompatible.canAssignToFrom;

@CustomLog
public class HaxeMethodAnnotator implements Annotator {

  @Override
  public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
      if (element instanceof HaxeMethod haxeMethod) {
        check(haxeMethod, holder);
      }
  }
  static public void check(final HaxeMethod methodPsi, final AnnotationHolder holder) {
    final HaxeMethodModel currentMethod = methodPsi.getModel();
    checkTypeTagInInterfacesAndExternClass(currentMethod, holder);
    checkMethodArguments(currentMethod, holder);
    checkOverride(methodPsi, holder);
    if (HaxeSemanticAnnotatorConfig.ENABLE_EXPERIMENTAL_BODY_CHECK) {
      checkBody(methodPsi, holder);
    }
    //currentMethod.getBodyPsi()
  }

  private static void checkTypeTagInInterfacesAndExternClass(final HaxeMethodModel currentMethod, final AnnotationHolder holder) {
    if (!MISSING_TYPE_TAG_ON_EXTERN_AND_INTERFACE.isEnabled(currentMethod.getBasePsi())) return;

    HaxeClassModel currentClass = currentMethod.getDeclaringClass();
    if (currentClass.isExtern() || currentClass.isInterface()) {
      if (currentMethod.getReturnTypeTagPsi() == null && !currentMethod.isConstructor()) {
        holder.newAnnotation(HighlightSeverity.ERROR, HaxeBundle.message("haxe.semantic.type.required"))
          .range(currentMethod.getNameOrBasePsi())
          .create();
      }
      for (final HaxeParameterModel param : currentMethod.getParameters()) {
        if (param.getTypeTagPsi() == null) {
          holder.newAnnotation(HighlightSeverity.ERROR,HaxeBundle.message("haxe.semantic.type.required"))
            .range(param.getBasePsi())
            .create();
        }
      }
    }
  }

  private static void checkMethodArguments(final HaxeMethodModel currentMethod, final AnnotationHolder holder) {
    PsiElement methodPsi = currentMethod.getBasePsi();
    boolean checkOptionalWithInit = OPTIONAL_WITH_INITIALIZER.isEnabled(methodPsi);
    boolean checkParameterInitializers = PARAMETER_INITIALIZER_TYPES.isEnabled(methodPsi);
    boolean checkParameterOrdering = PARAMETER_ORDERING_CHECK.isEnabled(methodPsi);
    boolean checkRepeatedParameterName = REPEATED_PARAMETER_NAME_CHECK.isEnabled(methodPsi);

    if (!checkOptionalWithInit
        && !checkParameterInitializers
        && !checkParameterOrdering
        && !checkRepeatedParameterName) {
      return;
    }

    boolean hasOptional = false;
    HashMap<String, PsiElement> argumentNames = new HashMap<String, PsiElement>();
    for (final HaxeParameterModel param : currentMethod.getParameters()) {
      String paramName = param.getName();

      if (checkOptionalWithInit) {
        if (param.hasOptionalPsi() && param.getVarInitPsi() != null) {
          // @TODO: Move to bundle
          holder.newAnnotation(HighlightSeverity.WARNING, "Optional not needed when specified an init value")
            .range(param.getOptionalPsi())
            .create();
        }
      }

      if (checkParameterInitializers) {
        if (param.getVarInitPsi() != null && param.getTypeTagPsi() != null) {
          HaxeSemanticsUtil.TypeTagChecker.check(
            param.getBasePsi(),
            param.getTypeTagPsi(),
            param.getVarInitPsi(),
            true,
            holder
          );
        }
      }

      if (checkParameterOrdering) {
        if (param.isOptional()) {
          hasOptional = true;
        }
        else if (hasOptional) {
          // @TODO: Move to bundle
          holder.newAnnotation(HighlightSeverity.WARNING, "Non-optional argument after optional argument")
            .range(param.getBasePsi())
            .create();
        }
      }

      if (checkRepeatedParameterName) {
        if (argumentNames.containsKey(paramName)) {
          // @TODO: Move to bundle
          holder.newAnnotation(HighlightSeverity.WARNING,"Repeated argument name '" + paramName + "'").range(param.getNamePsi()).create();
          holder.newAnnotation(HighlightSeverity.WARNING, "Repeated argument name '" + paramName + "'").range(argumentNames.get(paramName)).create();
        }
        else {
          argumentNames.put(paramName, param.getNamePsi());
        }
      }
    }
  }

  private static final String[] OVERRIDE_FORBIDDEN_MODIFIERS = {FINAL, INLINE, STATIC};

  private static void checkOverride(final HaxeMethod methodPsi, final AnnotationHolder holder) {
    final HaxeMethodModel currentMethod = methodPsi.getModel();
    final HaxeClassModel currentClass = currentMethod.getDeclaringClass();
    final HaxeModifiersModel currentModifiers = currentMethod.getModifiers();

    final HaxeClassModel parentClass = (currentClass != null) ? currentClass.getParentClass() : null;
    final HaxeMethodModel parentMethod = parentClass != null ? parentClass.getMethod(currentMethod.getName(), null) : null;
    final HaxeModifiersModel parentModifiers = (parentMethod != null) ? parentMethod.getModifiers() : null;

    if (!METHOD_OVERRIDE_CHECK.isEnabled(methodPsi)) { // TODO: This check is not granular enough.
      // If the rest of the checks are disabled, we don't want to inhibit the signature check.
      if (null != parentMethod) {
        checkMethodsSignatureCompatibility(currentMethod, parentMethod, holder);
      }
      return;
    }

    boolean requiredOverride = false;

    if (currentMethod.isConstructor()) {
      if (currentModifiers.hasModifier(STATIC)) {
        // @TODO: Move to bundle
        holder.newAnnotation(HighlightSeverity.ERROR, "Constructor can't be static").range(currentMethod.getNameOrBasePsi())
        .withFix(
          new HaxeModifierRemoveFixer(currentModifiers, STATIC)
        )
          .create();
      }
    }
    else if (currentMethod.isStaticInit()) {
      if (!currentModifiers.hasModifier(STATIC)) {
        holder.newAnnotation(HighlightSeverity.ERROR, "__init__ must be static").range(currentMethod.getNameOrBasePsi())
        .withFix(
          new HaxeModifierAddFixer(currentModifiers, STATIC)
        )
          .create();
      }
    }
    else if (parentMethod != null) {
      if (parentMethod.isStatic()) {
        holder.newAnnotation(HighlightSeverity.WARNING, "Method '" + currentMethod.getName()
                                                        + "' overrides a static method of a superclass")
          .range(currentMethod.getNameOrBasePsi())
          .create();
      }
      else {
        requiredOverride = true;

        if (parentModifiers.hasAnyModifier(OVERRIDE_FORBIDDEN_MODIFIERS)) {
          AnnotationBuilder builder = holder.newAnnotation(HighlightSeverity.ERROR, "Can't override static, inline or final methods")
           .range(currentMethod.getNameOrBasePsi());

          for (String modifier : OVERRIDE_FORBIDDEN_MODIFIERS) {
            if (parentModifiers.hasModifier(modifier)) {
              builder.withFix(
                new HaxeModifierRemoveFixer(parentModifiers, modifier, "Remove " + modifier + " from " + parentMethod.getFullName())
              );
            }
          }
          builder.create();
        }

        if (HaxePsiModifier.hasLowerVisibilityThan(currentModifiers.getVisibility(), parentModifiers.getVisibility())) {
          holder.newAnnotation(HighlightSeverity.WARNING, "Field " +
                                                          currentMethod.getName() +
                                                          " has less visibility (public/private) than superclass one.")
            .range(currentMethod.getNameOrBasePsi())
            .withFix(
              new HaxeModifierReplaceVisibilityFixer(currentModifiers, parentModifiers.getVisibility(), "Change current method visibility"))
            .withFix(
              new HaxeModifierReplaceVisibilityFixer(parentModifiers, currentModifiers.getVisibility(), "Change parent method visibility"))
            .create();
        }
      }
    }

    //System.out.println(aClass);
    if (currentModifiers.hasModifier(OVERRIDE) && !requiredOverride) {
      holder.newAnnotation(HighlightSeverity.ERROR, "Overriding nothing").range(currentModifiers.getModifierPsi(OVERRIDE))
        .withFix(new HaxeModifierRemoveFixer(currentModifiers, OVERRIDE))
        .create();
    }
    else if (requiredOverride) {
      if (!currentModifiers.hasModifier(OVERRIDE)) {
        holder.newAnnotation(HighlightSeverity.ERROR, "Must override").range(currentMethod.getNameOrBasePsi())
          .withFix(new HaxeModifierAddFixer(currentModifiers, OVERRIDE))
          .create();
      }
      else {
        // It is rightly overriden. Now check the signature.
        checkMethodsSignatureCompatibility(currentMethod, parentMethod, holder);
      }
    }
  }

  static void checkMethodsSignatureCompatibility(
    @NotNull final HaxeMethodModel currentMethod,
    @NotNull final HaxeMethodModel parentMethod,
    final AnnotationHolder holder
  ) {
    if (!METHOD_SIGNATURE_COMPATIBILITY.isEnabled(currentMethod.getBasePsi())) return;

    final HaxeDocumentModel document = currentMethod.getDocument();

    List<HaxeParameterModel> currentParameters = currentMethod.getParameters();
    final List<HaxeParameterModel> parentParameters = parentMethod.getParameters();
    int minParameters = Math.min(currentParameters.size(), parentParameters.size());

    if (currentParameters.size() > parentParameters.size()) {
      for (int n = minParameters; n < currentParameters.size(); n++) {
        final HaxeParameterModel currentParam = currentParameters.get(n);
        holder.newAnnotation(HighlightSeverity.ERROR,"Unexpected argument" ).range(currentParam.getBasePsi())
          .withFix(
          new HaxeFixer("Remove argument") {
            @Override
            public void run() {
              currentParam.remove();
            }
          })
          .create();
      }
    }
    else if (currentParameters.size() != parentParameters.size()) {
      holder.newAnnotation(HighlightSeverity.ERROR, "Not matching arity expected " +
                                                    parentParameters.size() +
                                                    " arguments but found " +
                                                    currentParameters.size())
        .range(currentMethod.getNameOrBasePsi())
        .create();

    }

    HaxeGenericResolver scopeResolver = HaxeGenericResolverUtil.generateResolverFromScopeParents(currentMethod.getBasePsi());

    for (int n = 0; n < minParameters; n++) {
      final HaxeParameterModel currentParam = currentParameters.get(n);
      final HaxeParameterModel parentParam = parentParameters.get(n);

      // We cannot simply check that the two types are the same when they have type arguments;
      // the arguments may not resolve to the same thing.  So, we need to resolve the element
      // in the super-class before we can check assignment compatibility.
      SpecificHaxeClassReference resolvedParent = resolveSuperclassElement(scopeResolver, currentParam, parentParam);

      // Order of assignment compatibility is to parent, from subclass.
      ResultHolder currentParamType = currentParam.getType(scopeResolver);
      ResultHolder parentParamType = parentParam.getType(null == resolvedParent ? scopeResolver : resolvedParent.getGenericResolver());
      if (!canAssignToFrom(parentParamType, currentParamType)) {

        typeMismatch(holder, currentParam.getBasePsi(), currentParamType.toString(), parentParamType.toString())
          .withFix(HaxeFixer.create(HaxeBundle.message("haxe.semantic.change.type"), () -> {
            document.replaceElementText(currentParam.getTypeTagPsi(), parentParam.getTypeTagPsi().getText());
          })).create();
      }

      if (currentParam.hasOptionalPsi() != parentParam.hasOptionalPsi()) {
        final boolean removeOptional = currentParam.hasOptionalPsi();

        String errorMessage;
        if (parentMethod.getDeclaringClass().isInterface()) {
          errorMessage = removeOptional ? "haxe.semantic.implemented.method.parameter.required"
                                        : "haxe.semantic.implemented.method.parameter.optional";
        }
        else {
          errorMessage = removeOptional ? "haxe.semantic.overwritten.method.parameter.required"
                                        : "haxe.semantic.overwritten.method.parameter.optional";
        }

        errorMessage = HaxeBundle.message(errorMessage, parentParam.getPresentableText(scopeResolver),
                                          parentMethod.getDeclaringClass().getName() + "." + parentMethod.getName());


        final String localFixName = HaxeBundle.message(removeOptional ? "haxe.semantic.method.parameter.optional.remove"
                                                                      : "haxe.semantic.method.parameter.optional.add");

        holder.newAnnotation(HighlightSeverity.ERROR, errorMessage)
          .range(currentParam.getBasePsi())
          .withFix(
            new HaxeFixer(localFixName) {
              @Override
              public void run() {
                if (removeOptional) {
                  currentParam.getOptionalPsi().delete();
                }
                else {
                  PsiElement element = currentParam.getBasePsi();
                  document.addTextBeforeElement(element.getFirstChild(), "?");
                }
              }
            }
          )
          .create();
      }
    }

    // Check the return type...

    // Again, the super-class may resolve with different/incompatible type arguments.
    SpecificHaxeClassReference resolvedParent = resolveSuperclassElement(scopeResolver, currentMethod, parentMethod);

    ResultHolder currentResult = currentMethod.getResultType(scopeResolver);
    ResultHolder parentResult = parentMethod.getResultType(resolvedParent != null ? resolvedParent.getGenericResolver() : scopeResolver);

    // Order of assignment compatibility is to parent, from subclass.
    if (!canAssignToFrom(parentResult.getType(), currentResult.getType())) {
      PsiElement psi = currentMethod.getReturnTypeTagOrNameOrBasePsi();
      if (parentResult.getType().isUnknown()) {
        holder.newAnnotation(HighlightSeverity.WEAK_WARNING,HaxeBundle.message("haxe.unresolved.type"))
          .range(psi.getTextRange())
          .create();
      }
      else {

        returnTypeMismatch(holder, psi, currentResult.getType().toStringWithoutConstant(), parentResult.getType().toStringWithConstant())
          .withFix(HaxeFixer.create(HaxeBundle.message("haxe.semantic.change.type"), () -> {
            document.replaceElementText(currentResult.getElementContext(), parentResult.toStringWithoutConstant());
          })).create();
      }
    }
  }

  @Nullable
  private static SpecificHaxeClassReference resolveSuperclassElement(HaxeGenericResolver scopeResolver,
                                                                     HaxeModel currentElement,
                                                                     HaxeModel parentParam) {
    HaxeGenericSpecialization scopeSpecialization =
      HaxeGenericSpecialization.fromGenericResolver(currentElement.getBasePsi(), scopeResolver);
    HaxeClassResolveResult superclassResult = HaxeResolveUtil.getSuperclassResolveResult(parentParam.getBasePsi(),
                                                                                         currentElement.getBasePsi(),
                                                                                         scopeSpecialization);
    if (superclassResult == HaxeClassResolveResult.EMPTY) {
      // TODO: Create Unresolved annotation??
      log.warn("Couldn't resolve a parameter type from a subclass for " + currentElement.getName());
    }

    SpecificHaxeClassReference resolvedParent = null;
    HaxeGenericResolver superResolver = superclassResult.getGenericResolver();
    HaxeClass superClass = superclassResult.getHaxeClass();
    if (null != superClass) {
      HaxeClassReference superclassReference = new HaxeClassReference(superClass.getModel(), currentElement.getBasePsi());
      resolvedParent = SpecificHaxeClassReference.withGenerics(superclassReference, superResolver.getSpecificsFor(superClass));
    }
    return resolvedParent;
  }

  // Fast check without annotations
  static boolean checkIfMethodSignatureDiffers(HaxeMethodModel source, HaxeMethodModel prototype) {
    final List<HaxeParameterModel> sourceParameters = source.getParameters();
    final List<HaxeParameterModel> prototypeParameters = prototype.getParameters();

    if (sourceParameters.size() != prototypeParameters.size()) {
      return true;
    }

    final int parametersCount = sourceParameters.size();

    for (int n = 0; n < parametersCount; n++) {
      final HaxeParameterModel sourceParam = sourceParameters.get(n);
      final HaxeParameterModel prototypeParam = prototypeParameters.get(n);
      if (!canAssignToFrom(sourceParam.getType(), prototypeParam.getType()) ||
          sourceParam.isOptional() != prototypeParam.isOptional()) {
        return true;
      }
    }

    ResultHolder currentResult = source.getResultType();
    ResultHolder prototypeResult = prototype.getResultType();

    return !currentResult.canAssign(prototypeResult);
  }


  public static void checkBody(HaxeMethod psi, AnnotationHolder holder) {
    final HaxeMethodModel method = psi.getModel();
    // Note: getPsiElementType runs a number of checks while determining the type.
    HaxeTypeResolver.getPsiElementType(method.getBodyPsi(), holder, generateConstraintResolver(method));
  }

  @NotNull
  private static HaxeGenericResolver generateConstraintResolver(HaxeMethodModel method) {
    HaxeGenericResolver resolver = new HaxeGenericResolver();
    for (HaxeGenericParamModel param : method.getGenericParams()) {
      ResultHolder constraint = param.getConstraint(resolver);
      if (null == constraint) {
        constraint = new ResultHolder(SpecificHaxeClassReference.getDynamic(param.getPsi()));
      }
      resolver.add(param.getName(), constraint);
    }
    return resolver;
  }
}