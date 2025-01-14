package com.intellij.plugins.haxe.model.evaluator;

import com.intellij.lang.ASTNode;
import com.intellij.lang.annotation.AnnotationBuilder;
import com.intellij.openapi.util.RecursionGuard;
import com.intellij.openapi.util.RecursionManager;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.ide.annotator.HaxeStandardAnnotation;
import com.intellij.plugins.haxe.ide.annotator.semantics.HaxeCallExpressionUtil;
import com.intellij.plugins.haxe.lang.lexer.HaxeEmbeddedElementType;
import com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets;
import com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.lang.psi.impl.AbstractHaxeNamedComponent;
import com.intellij.plugins.haxe.lang.psi.impl.HaxeReferenceExpressionImpl;
import com.intellij.plugins.haxe.model.*;
import com.intellij.plugins.haxe.model.fixer.*;
import com.intellij.plugins.haxe.model.type.*;
import com.intellij.plugins.haxe.model.type.resolver.ResolveSource;
import com.intellij.plugins.haxe.util.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.tree.LazyParseablePsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Stream;

import static com.intellij.plugins.haxe.ide.annotator.semantics.HaxeCallExpressionUtil.tryGetCallieType;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypeSets.ONLY_COMMENTS;
import static com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes.KUNTYPED;
import static com.intellij.plugins.haxe.lang.psi.impl.HaxeReferenceImpl.getLiteralClassName;
import static com.intellij.plugins.haxe.lang.psi.impl.HaxeReferenceImpl.tryToFindTypeFromCallExpression;
import static com.intellij.plugins.haxe.model.evaluator.HaxeExpressionUsageUtil.searchReferencesForTypeParameters;
import static com.intellij.plugins.haxe.model.evaluator.HaxeExpressionUsageUtil.tryToFindTypeFromUsage;
import static com.intellij.plugins.haxe.model.type.HaxeGenericResolverUtil.createInheritedClassResolver;
import static com.intellij.plugins.haxe.model.type.HaxeMacroUtil.resolveMacroTypesForFunction;
import static com.intellij.plugins.haxe.model.type.SpecificTypeReference.*;
import static com.intellij.plugins.haxe.model.evaluator.HaxeExpressionEvaluator.*;

@CustomLog
public class HaxeExpressionEvaluatorHandlers {


  private static final RecursionGuard<PsiElement>
    evaluatorHandlersRecursionGuard = RecursionManager.createGuard("EvaluatorHandlersRecursionGuard");

  @Nullable
  static ResultHolder handleWithRecursionGuard(PsiElement element,
                                               HaxeExpressionEvaluatorContext context,
                                               HaxeGenericResolver resolver) {

    if (element == null ) return null;
    return evaluatorHandlersRecursionGuard.doPreventingRecursion(element, true, () -> handle(element, context, resolver));
  }


  static ResultHolder handleTernaryExpression(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeTernaryExpression ternaryExpression) {
    HaxeExpression[] list = ternaryExpression.getExpressionList().toArray(new HaxeExpression[0]);
    SpecificTypeReference type1 = handle(list[1], context, resolver).getType();
    SpecificTypeReference type2 = handle(list[2], context, resolver).getType();
    return HaxeTypeUnifier.unify(type1, type2, ternaryExpression, context.getScope().unificationRules)
      .createHolder();
  }

  static ResultHolder handleBinaryExpression(HaxeExpressionEvaluatorContext context, HaxeGenericResolver resolver,
                                                     HaxeBinaryExpression expression) {
    if (
      (expression instanceof HaxeAdditiveExpression) ||
      (expression instanceof HaxeModuloExpression) ||
      (expression instanceof HaxeBitwiseExpression) ||
      (expression instanceof HaxeShiftExpression) ||
      (expression instanceof HaxeLogicAndExpression) ||
      (expression instanceof HaxeLogicOrExpression) ||
      (expression instanceof HaxeCompareExpression) ||
      (expression instanceof HaxeCoalescingExpression) ||
      (expression instanceof HaxeMultiplicativeExpression)
    ) {
      PsiElement[] children = expression.getChildren();
      String operatorText;
      if (children.length == 3) {
        operatorText = children[1].getText();
        SpecificTypeReference left = handle(children[0], context, resolver).getType();
        SpecificTypeReference right = handle(children[2], context, resolver).getType();
        left = resolveAnyTypeDefs(left);
        right = resolveAnyTypeDefs(right);
        // we might have constraints that help up here
        if(left.isTypeParameter())  left = tryResolveTypeParameter(left, resolver);
        if(right.isTypeParameter())  right = tryResolveTypeParameter(right, resolver);

        return HaxeOperatorResolver.getBinaryOperatorResult(expression, left, right, operatorText, context).createHolder();
      }
      else {
        operatorText = getOperator(expression, HaxeTokenTypeSets.OPERATORS);
        SpecificTypeReference left = handle(children[0], context, resolver).getType();
        SpecificTypeReference right = handle(children[1], context, resolver).getType();
        left = resolveAnyTypeDefs(left);
        right = resolveAnyTypeDefs(right);
        // we might have constraints that help up here
        if(left.isTypeParameter())  left = tryResolveTypeParameter(left, resolver);
        if(right.isTypeParameter())  right = tryResolveTypeParameter(right, resolver);

        return HaxeOperatorResolver.getBinaryOperatorResult(expression, left, right, operatorText, context).createHolder();
      }
    }
    return createUnknown(expression);
  }

  private static SpecificTypeReference tryResolveTypeParameter(SpecificTypeReference typeParam, HaxeGenericResolver resolver) {
    ResultHolder resolve = resolver.resolve(typeParam.createHolder());
    if( resolve != null && !resolve.isUnknown()) {
      return resolve.getType();
    }
    return typeParam;
  }

  static ResultHolder handleTypeCheckExpr(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeTypeCheckExpr typeCheckExpr) {
    PsiElement[] children = typeCheckExpr.getChildren();
    if (children.length == 2) {
      SpecificTypeReference statementType = handle(children[0], context, resolver).getType();
      SpecificTypeReference assertedType = SpecificTypeReference.getUnknown(children[1]);
      if (children[1] instanceof HaxeTypeOrAnonymous) {
        HaxeTypeOrAnonymous toa = typeCheckExpr.getTypeOrAnonymous();
        if (toa != null ) {
          assertedType = HaxeTypeResolver.getTypeFromTypeOrAnonymous(toa).getType();
        }
      }
      // When we have proper unification (not failing to dynamic), then we should be checking if the
      // values unify.
      //SpecificTypeReference unified = HaxeTypeUnifier.unify(statementType, assertedType, element);
      //if (!unified.canAssign(statementType)) {
      if (!assertedType.canAssign(statementType)) {
        context.addError(typeCheckExpr, "Statement of type '" + statementType.getElementContext().getText() + "' does not unify with asserted type '" + assertedType.getElementContext().getText() + ".'");
        // TODO: Develop some fixers.
        // annotation.registerFix(new HaxeCreateLocalVariableFixer(accessName, element));
      }

      return statementType.createHolder();
    }
    return createUnknown(typeCheckExpr);
  }

  static ResultHolder handleGuard(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeGuard haxeGuard) {
    HaxeExpression guardExpression = haxeGuard.getExpression();
    SpecificTypeReference expr = handle(guardExpression, context, resolver).getType();
    if (!SpecificTypeReference.getBool(haxeGuard).canAssign(expr)) {
      context.addError(
        guardExpression,
        "If expr " + expr + " should be bool",
        new HaxeCastFixer(guardExpression, expr, SpecificHaxeClassReference.getBool(haxeGuard))
      );
    }

    if (expr.isConstant()) {
      context.addWarning(guardExpression, "If expression constant");
    }
    return expr.createHolder();
  }

  @Nullable
  static ResultHolder handleReferenceExpression( HaxeExpressionEvaluatorContext context, HaxeGenericResolver resolver,
                                                         HaxeReferenceExpression element) {
    PsiElement[] children = element.getChildren();
    ResultHolder typeHolder = null;
    if (children.length == 0) {
       typeHolder  = SpecificTypeReference.getUnknown(element).createHolder();
    }else {
      PsiElement firstChild = children[0];
      // make sure  expression  is not something like  `var myVar = myVar.add(x)`, we cant resolve type from this
      if (firstChild != element) {
        typeHolder = handle(firstChild, context, resolver);
      }else {
        typeHolder  = SpecificTypeReference.getUnknown(element).createHolder();
      }
    }

    boolean resolved = !typeHolder.getType().isUnknown();
    for (int n = 1; n < children.length; n++) {
      PsiElement child = children[n];
      SpecificTypeReference typeReference = typeHolder.getType();
      if (typeReference.isString() && typeReference.isConstant() && child.textMatches("code")) {
        String str = (String)typeReference.getConstant();
        typeHolder = SpecificTypeReference.getInt(element, (str != null && !str.isEmpty()) ? str.charAt(0) : -1).createHolder();
        if (str == null || str.length() != 1) {
          context.addError(element, "String must be a single UTF8 char");
        }
      } else {

        if (typeReference.isUnknown()) continue;

        if (typeReference.isNullType()) {
          typeHolder = typeHolder.tryUnwrapNullType();
        }

        // TODO: Yo! Eric!!  This needs to get fixed.  The resolver is coming back as Dynamic, when it should be String

        // Grab the types out of the original resolver (so we don't modify it), and overwrite them
        // (by adding) with the class' resolver. That way, we get the combination of the two, and
        // any parameters provided/set in the class will override any from the calling context.
        HaxeGenericResolver localResolver = new HaxeGenericResolver();
        localResolver.addAll(resolver);

        SpecificHaxeClassReference classType = typeHolder.getClassType();
        if (null != classType) {
          localResolver = localResolver.withoutClassTypeParameters();
          localResolver.addAll(classType.getGenericResolver());
        }
        String accessName = child.getText();
        ResultHolder access = typeHolder.getType().access(accessName, context, localResolver);
        if (access == null) {
          resolved = false;

          if (children.length == 1) {
            context.addError(children[n], "Can't resolve '" + accessName + "' in " + typeHolder.getType());
          }
          else {
            context.addError(children[n], "Can't resolve '" + accessName + "' in " + typeHolder.getType());
          }

        }
        if (access != null) typeHolder = access;
      }
    }

    // If we aren't walking the body, then we might not have seen the reference.  In that
    // case, the type is still unknown.  Let's see if the resolver can figure it out.
    PsiElement subelement = null;
    if (!resolved) {
      PsiReference reference = element.getReference();
      if (reference != null) {
        subelement = reference.resolve();
        if (subelement != element) {
          if (subelement instanceof HaxeReferenceExpression referenceExpression) {
            PsiElement resolve = referenceExpression.resolve();
            if (resolve != element)
              typeHolder = handleWithRecursionGuard(resolve, context, resolver);
          }
          if (subelement instanceof HaxeClass haxeClass) {

            HaxeClassModel model = haxeClass.getModel();
            HaxeClassReference classReference = new HaxeClassReference(model, element);

            if (haxeClass.isGeneric()) {
              @NotNull ResultHolder[] specifics = resolver.getSpecificsFor(classReference);
              SpecificHaxeClassReference specificReference = SpecificHaxeClassReference.withGenerics(classReference, specifics);
              // hackish way to ignore typeParameters for dynamic if not in expression
              if (specificReference.isDynamic() && element.textMatches("Dynamic")) {
                specificReference = SpecificHaxeClassReference.getDynamic(element);
              }
              typeHolder = specificReference.createHolder();
            }
            else {
              typeHolder = SpecificHaxeClassReference.withoutGenerics(classReference).createHolder();
            }

            // check if pure Class Reference
            if (reference instanceof HaxeReferenceExpressionImpl expression) {
              if (expression.isPureClassReferenceOf(haxeClass.getName())) {
                // wrap in Class<> or Enum<>
                SpecificHaxeClassReference originalClass = SpecificHaxeClassReference.withoutGenerics(model.getReference());
                SpecificHaxeClassReference wrappedClass =
                  SpecificHaxeClassReference.getStdClass(haxeClass.isEnum() ? ENUM : CLASS, element,
                                                         new ResultHolder[]{new ResultHolder(originalClass)});
                typeHolder = wrappedClass.createHolder();
              }
            }
          }
          else if (subelement instanceof HaxeFieldDeclaration fieldDeclaration) {
            HaxeVarInit init = fieldDeclaration.getVarInit();
            if (init != null) {
              HaxeExpression initExpression = init.getExpression();
              HaxeGenericResolver initResolver = HaxeGenericResolverUtil.generateResolverFromScopeParents(initExpression);
              typeHolder = HaxeTypeResolver.getFieldOrMethodReturnType((AbstractHaxeNamedComponent)subelement, initResolver);
            }
            else {
              HaxeTypeTag tag = fieldDeclaration.getTypeTag();
              if (tag != null) {
                typeHolder = HaxeTypeResolver.getTypeFromTypeTag(tag, fieldDeclaration);
                HaxeClass  usedIn = PsiTreeUtil.getParentOfType((PsiElement)reference, HaxeClass.class);
                HaxeClass containingClass = (HaxeClass)fieldDeclaration.getContainingClass();
                if (usedIn != null && containingClass != null && usedIn != containingClass && containingClass.isGeneric()) {
                  HaxeGenericResolver inheritedClassResolver = createInheritedClassResolver(containingClass, usedIn, resolver);
                  HaxeGenericResolver resolverForContainingClass = inheritedClassResolver.getSpecialization(null).toGenericResolver(containingClass);
                  ResultHolder resolve = resolverForContainingClass.resolve(typeHolder);
                  if (resolve != null && !resolve.isUnknown()) typeHolder = resolve;
                }else if (typeHolder.isTypeParameter()) {
                  ResultHolder resolve = resolver.resolve(typeHolder);
                  if(resolve != null && !resolve.isUnknown()) {
                    typeHolder = resolve;
                  }
                }

              }
            }
          }
          else if (subelement instanceof HaxeMethod haxeMethod) {
            boolean isFromCallExpression = reference instanceof  HaxeCallExpression;
            SpecificFunctionReference type = haxeMethod.getModel().getFunctionType(isFromCallExpression ? resolver : resolver.withoutAssignHint());
            if (!isFromCallExpression) {
              //  expression is referring to the method not calling it.
              //  assign hint should be used for substituting parameters instead of being used as return type
              type = resolver.substituteTypeParamsWithAssignHintTypes(type);
            }
            typeHolder = type.createHolder();
          }

          else if (subelement instanceof HaxeValueIterator valueIterator) {
            typeHolder = handleValueIterator(context, resolver, valueIterator);
          }

          else if (subelement instanceof HaxeIteratorkey || subelement instanceof HaxeIteratorValue) {
            typeHolder = findIteratorType(subelement);
          }
          // case MyEnum(ref)
          else if (subelement instanceof HaxeEnumExtractedValue extractedValue) {
            typeHolder = handle(extractedValue.getExpression(),context,resolver);
          }

          // case var x; / case x = ...;
          else if (subelement instanceof HaxeSwitchCaseCaptureVar || subelement instanceof  HaxeSwitchCaseCapture) {
            HaxeEnumArgumentExtractor argumentExtractor =  PsiTreeUtil.getParentOfType(subelement, HaxeEnumArgumentExtractor.class, true, HaxeSwitchStatement.class);
            // if reference is in an argument extractor, get type from enum constructor parameter list (typical "case MyEnumVal( x = {..}")
            if (argumentExtractor != null) {
              List<@NotNull PsiElement> argExtractChildren = Arrays.asList(argumentExtractor.getEnumExtractorArgumentList().getChildren());
              int index = argExtractChildren.indexOf(subelement);
              if (index > -1) {
                PsiElement enumConsPsi = argumentExtractor.getEnumValueReference().getReferenceExpression().resolve();
                if (enumConsPsi instanceof HaxeEnumValueDeclarationConstructor constructor) {
                  HaxeParameterList parameterList = constructor.getParameterList();
                  List<HaxeParameter> list = parameterList.getParameterList();
                  if (index < list.size()) {
                    HaxeParameter parameter = list.get(index);
                    return handle(parameter, context, resolver);
                  }
                }
              }
            }
            // if not in an arg extractor, then use type from switch (typical in  "case var x:" and "case x = ..." )
            HaxeSwitchStatement switchStatement = PsiTreeUtil.getParentOfType(subelement, HaxeSwitchStatement.class);
            if (switchStatement.getExpression() != null) {
              return handle(switchStatement.getExpression(), context, resolver);
          }
          }

          else if (subelement instanceof HaxeSwitchCaseExpr caseExpr) {
            HaxeSwitchStatement switchStatement = PsiTreeUtil.getParentOfType(caseExpr, HaxeSwitchStatement.class);
            if (switchStatement.getExpression() != null) {
              typeHolder = handle(switchStatement.getExpression(), context, resolver);
            }
          }

          else {
            // attempt to resolve sub-element using default handle logic
            if (!(subelement instanceof PsiPackage)) {
              typeHolder = handleWithRecursionGuard(subelement, context, resolver);
            }
          }
        }
      }
    }

    if (typeHolder != null) {
      if (isReificationReference(element)) {
        ResultHolder specifics = tryExtractTypeFormExprOf(element, typeHolder);
        if (specifics != null) return specifics;
      }

      if (isReificationExpression(element)) {
        return HaxeMacroTypeUtil.getExpr(element).createHolder();
      }

      if (subelement instanceof HaxeImportAlias) return typeHolder;
      // overriding  context  to avoid problems with canAssign thinking this is a "Pure" class reference
      if (!typeHolder.isFunctionType())  return typeHolder.withElementContext(element);
      return typeHolder;
    }

    return typeHolder;
    //return SpecificTypeReference.getDynamic(element).createHolder();
  }

  private static boolean isReificationExpression(HaxeReferenceExpression element) {
    return false;
  }

  private static @Nullable ResultHolder tryExtractTypeFormExprOf(HaxeReferenceExpression element, ResultHolder typeHolder) {
    SpecificHaxeClassReference type = typeHolder.getClassType();
    if (type != null && type.getHaxeClass() != null) {
      String qualifiedName = type.getHaxeClass().getQualifiedName();
      if (qualifiedName.equals(HaxeMacroTypeUtil.EXPR_OF)){
        @NotNull ResultHolder[] specifics = type.getSpecifics();
        if (specifics.length == 1) return specifics[0];
      }else if (qualifiedName.equals(HaxeMacroTypeUtil.EXPR)){
        SpecificTypeReference.getDynamic(element).createHolder();
      }
    }
    return null;
  }

  private static boolean isReificationReference(HaxeReferenceExpression element) {
    @NotNull PsiElement[] children = element.getChildren();
    if (children.length == 1) {
      if (children[0] instanceof HaxeMacroIdentifier identifier) {
        return identifier.getMacroId() != null;
      }
    }
    return false;
  }

  static ResultHolder handleValueIterator(HaxeExpressionEvaluatorContext context,
                                        HaxeGenericResolver resolver,
                                        HaxeValueIterator valueIterator) {
    HaxeForStatement forStatement = PsiTreeUtil.getParentOfType(valueIterator, HaxeForStatement.class);
    if (forStatement != null) {
        final HaxeIterable iterable = forStatement.getIterable();
        if (iterable != null) {
          // NOTE do not forward resolver here, this is a different expression and might have its own typeParameters
          ResultHolder iterator = handle(iterable, context, null);
          if (iterator.isClassType()) {
            iterator = iterator.getClassType().fullyResolveTypeDefAndUnwrapNullTypeReference().createHolder();
          }
          // get specific from iterator as thats the type for our variable
          ResultHolder[] specifics = iterator.getClassType().getSpecifics();
          if (specifics.length > 0) {
            return specifics[0];
          }
      }
    }
    return createUnknown(valueIterator);
  }

  static ResultHolder handleRegularExpressionLiteral(HaxeRegularExpressionLiteral regexLiteral) {
    HaxeClass regexClass = HaxeResolveUtil.findClassByQName(getLiteralClassName(HaxeTokenTypes.REG_EXP), regexLiteral);
    if (regexClass != null) {
      return SpecificHaxeClassReference.withoutGenerics(new HaxeClassReference(regexClass.getModel(), regexLiteral)).createHolder();
    }
    return createUnknown(regexLiteral);
  }

  static ResultHolder handleStringLiteralExpression(PsiElement element) {
    // @TODO: check if it has string interpolation inside, in that case text is not constant
    String constant = HaxeStringUtil.unescapeString(element.getText());
    return SpecificHaxeClassReference.primitive("String", element, constant).createHolder();
  }

  static ResultHolder handleSwitchCaseCaptureVar(HaxeGenericResolver resolver, HaxeSwitchCaseCaptureVar captureVar) {
    HaxeResolveResult result = HaxeResolveUtil.getHaxeClassResolveResult(captureVar, resolver.getSpecialization(null));
    if (result.isHaxeClass()) {
      return result.getSpecificClassReference(result.getHaxeClass(), resolver).createHolder();
    }else if (result.isFunctionType()) {
      return result.getSpecificClassReference(result.getFunctionType(), resolver).createHolder();
    }
    return createUnknown(captureVar);
  }

  static ResultHolder handleFieldDeclaration(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeFieldDeclaration declaration) {
    HaxeTypeTag typeTag = declaration.getTypeTag();

    if (typeTag!= null) {
      return HaxeTypeResolver.getTypeFromTypeTag(typeTag, declaration);
    }else {
      HaxeVarInit init = declaration.getVarInit();
      if (init != null) {
        ResultHolder result = handle(init.getExpression(), context, resolver);
        if (isDynamicBecauseOfNullValueInit(result)) {
          HaxeComponentName element = declaration.getComponentName();
          final ResultHolder hint = result;
          result = tryToFindTypeFromUsage(element, result, hint, context, resolver, null);
        }
        return result;
      }
    }
    return createUnknown(declaration);
  }

  static ResultHolder handleSpreadExpression(HaxeGenericResolver resolver, HaxeSpreadExpression spreadExpression) {
    HaxeExpression expression = spreadExpression.getExpression();
    // we treat restParameters as arrays, so we need to "unwrap" the array to get the correct type.
    // (currently restParameters and Arrays are the only types you can spread afaik. and only in method calls)
    if (expression instanceof HaxeReferenceExpression referenceExpression) {
      ResultHolder type = HaxeTypeResolver.getPsiElementType(referenceExpression, resolver);
      if (type.isClassType()) {
        ResultHolder[] specifics = type.getClassType().getSpecifics();
        if (specifics.length == 1) {
          return specifics[0];
        }
      }
    }
    else if (expression instanceof HaxeArrayLiteral arrayLiteral) {
      HaxeResolveResult result = arrayLiteral.resolveHaxeClass();
      SpecificHaxeClassReference reference = result.getSpecificClassReference(expression, resolver);
      @NotNull ResultHolder[] specifics = reference.getSpecifics();
      if (specifics.length == 1) {
        return specifics[0];
      }
    }
    return createUnknown(spreadExpression);
  }


  static ResultHolder handleParameter(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeParameter parameter) {
    HaxeTypeTag typeTag = parameter.getTypeTag();
    if (typeTag != null) {
      ResultHolder typeFromTypeTag = HaxeTypeResolver.getTypeFromTypeTag(typeTag, parameter);
      ResultHolder resolve = resolver.resolve(typeFromTypeTag);
      if (resolve != null && !resolve.isUnknown()) typeFromTypeTag = resolve;
      // if parameter is optional then its nullable and should be Null<T>
      if (parameter.getOptionalMark() != null && !typeFromTypeTag.isNullWrappedType()) {
        return typeFromTypeTag.wrapInNullType();
      }
      return typeFromTypeTag;
    }

    HaxeVarInit init = parameter.getVarInit();
    if (init != null) {
      ResultHolder holder = handle(init, context, resolver);
      if (!holder.isUnknown()) {
        if (parameter.getOptionalMark() != null && !holder.isNullWrappedType()) {
          // if parameter is optional then its nullable and should be Null<T>
          return holder.wrapInNullType();
        }
        return holder;
      }
    }else {
      if (parameter.getParent().getParent() instanceof HaxeFunctionLiteral functionLiteral) {
        ResultHolder holder = null;
        RecursionManager.markStack();
          holder = tryToFindTypeFromCallExpression(functionLiteral, parameter);
          if (holder == null || holder.containsTypeParameters()) {
            HaxeComponentName name = parameter.getComponentName();
            final ResultHolder hint = holder;
            ResultHolder searchResult =  evaluatorHandlersRecursionGuard.computePreventingRecursion(name, true, () -> {
                return searchReferencesForType(name, context, resolver, functionLiteral, hint);
            });
            if (searchResult!= null && !searchResult.isUnknown()) holder = searchResult;
          }

        if (holder!= null && !holder.isUnknown()) {
          ResultHolder resolve = resolver.resolve(holder);
          return resolve != null && !resolve.isUnknown() ? resolve : holder;
        }else {
          return createUnknown(parameter);
        }
      }else {
        HaxeMethod method = PsiTreeUtil.getParentOfType(parameter, HaxeMethod.class);
        ResultHolder holder = searchReferencesForType(parameter.getComponentName(), context, resolver, method.getBody());
        if (holder!= null && !holder.isUnknown()) {
          return holder;
        }
      }
    }
    return createUnknown(parameter);
  }

  static ResultHolder handleNewExpression(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeNewExpression expression) {
    HaxeType type = expression.getType();
    if (type != null) {
      if (isMacroVariable(type.getReferenceExpression().getIdentifier())){
        return SpecificTypeReference.getDynamic(expression).createHolder();
      }
      ResultHolder hint = resolver.getAssignHint();
      // remove Null wrapping in hints, a new expression can not be Null<> and just adds another unnecessary layer to the generic resolvers
      if(hint != null && hint.isNullWrappedType()) {
        hint = hint.tryUnwrapNullType();
      }

      ResultHolder typeHolder = HaxeTypeResolver.getTypeFromType(type, resolver);
      if (hint != null && hint.isClassType()) {
        HaxeGenericResolver localResolver = new HaxeGenericResolver();
        HaxeGenericResolver hintsResolver = hint.getClassType().getGenericResolver();
        localResolver.addAll(hintsResolver);
        ResultHolder resolvedWithHint = localResolver.resolve(typeHolder);
        if (resolvedWithHint != null && !resolvedWithHint.isUnknown()) typeHolder = resolvedWithHint;
      }

      if (!typeHolder.isUnknown() && typeHolder.getClassType() != null) {
        SpecificHaxeClassReference classReference = typeHolder.getClassType();
        HaxeClassModel classModel = classReference.getHaxeClassModel();
        HaxeGenericResolver classResolver = classReference.getGenericResolver();
        if (classModel != null) {
          HaxeMethodModel constructor = classModel.getConstructor(classResolver);
          if (constructor != null) {
            HaxeMethod method = constructor.getMethod();
            HaxeMethodModel methodModel = method.getModel();
            if (methodModel.getGenericParams().isEmpty()) {
              //TODO needs stackoverflow protecting ?
              HaxeCallExpressionUtil.CallExpressionValidation validation = HaxeCallExpressionUtil.checkConstructor(expression);
              HaxeGenericResolver resolverFromCallExpression = validation.getResolver();

              if (resolverFromCallExpression != null) {
                ResultHolder resolve = resolverFromCallExpression.resolve(typeHolder);
                if (!resolve.isUnknown()) typeHolder = resolve;
              }
            }
          }
        }
      }

      if (typeHolder.getType() instanceof SpecificHaxeClassReference classReference) {
        final HaxeClassModel clazz = classReference.getHaxeClassModel();
        if (clazz != null) {
          HaxeMethodModel constructor = clazz.getConstructor(resolver);
          if (constructor == null) {
            context.addError(expression, "Class " + clazz.getName() + " doesn't have a constructor", new HaxeFixer("Create constructor") {
              @Override
              public void run() {
                // @TODO: Check arguments
                clazz.addMethod("new");
              }
            });
          } else {
            //checkParameters(element, constructor, expression.getExpressionList(), context, resolver);
          }
        }
      }
      return typeHolder.duplicate();
    }
    return createUnknown(expression);
  }


  static ResultHolder handleEnumExtractedValue(@NotNull HaxeEnumExtractedValueReference extractedValue, @NotNull HaxeGenericResolver resolver) {
    HaxeEnumArgumentExtractor extractor = PsiTreeUtil.getParentOfType(extractedValue, HaxeEnumArgumentExtractor.class);
    if (extractor != null) {
      HaxeEnumExtractorModel extractorModel = (HaxeEnumExtractorModel)extractor.getModel();
      return extractorModel.resolveExtractedValueType(extractedValue, resolver);
    }
    return createUnknown(extractedValue);
  }



  static ResultHolder handleForStatement(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeForStatement forStatement) {
    final HaxeExpression forStatementExpression = forStatement.getExpression();
    final HaxeKeyValueIterator keyValueIterator = forStatement.getKeyValueIterator();
    final HaxeValueIterator valueIterator = forStatement.getValueIterator();
    final HaxeIterable iterable = forStatement.getIterable();
    final PsiElement body = forStatement.getLastChild();
    context.beginScope();

    if(context.getScope().deepSearchForReturnValues) handle(body, context, resolver);

    try {
      final SpecificTypeReference iterableValue = handle(iterable, context, resolver).getType();
      ResultHolder iteratorResult = iterableValue.getIterableElementType(resolver);
      SpecificTypeReference type = iteratorResult != null ? iteratorResult.getType() : null;
      //TODO: HACK?
      // String class in in standard lib is  currently missing iterator methods
      // this is a workaround  so we can iterate on chars in string.
      if (type == null && iterableValue.isString()) {
        type = iterableValue;
      }

      if (type != null) {
        if (forStatementExpression != null) {
          ResultHolder handle = handle(forStatementExpression, context, resolver);
          return handle.getType().createHolder();
        }
        if (type.isTypeParameter()) {
          if (iterable!= null && iterable.getExpression() instanceof  HaxeReference reference) {
            HaxeResolveResult result = reference.resolveHaxeClass();
            HaxeGenericResolver classResolver = result.getGenericResolver();
            ResultHolder holder = type.createHolder();
            ResultHolder resolved = classResolver.resolve(holder);
            if (resolved != null && !resolved.isUnknown()) {
              type = resolved.getType();
            }
          }
        }
      }
      if ( type != null) {
        if (iterableValue.isConstant()) {
          if (iterableValue.getConstant() instanceof HaxeRange constant) {
            type = type.withRangeConstraint(constant);
          }
        }
        if (valueIterator != null) {
          HaxeComponentName name = valueIterator.getComponentName();
            context.setLocal(name.getText(), new ResultHolder(type));
          } else if (keyValueIterator != null) {
            context.setLocal(keyValueIterator.getIteratorkey().getComponentName().getText(), new ResultHolder(type));
            context.setLocal(keyValueIterator.getIteratorValue().getComponentName().getText(), new ResultHolder(type));
          }
        return handle(body, context, resolver);
      }
      //attempt to get type from body (could be function call etc.)
      if (body != null) return handle(body, context, resolver);
    }
    finally {
      context.endScope();
    }
    return createUnknown(forStatement);
  }

  static ResultHolder createUnknown(PsiElement element) {
    return SpecificHaxeClassReference.getUnknown(element).createHolder();
  }

  static ResultHolder handlePrefixExpression(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxePrefixExpression prefixExpression) {
    HaxeExpression expression = prefixExpression.getExpression();
    ResultHolder typeHolder = handle(expression, context, resolver);
    SpecificTypeReference type = typeHolder.getType();
    if (type.getConstant() != null) {
      String operatorText = getOperator(prefixExpression, HaxeTokenTypeSets.OPERATORS);
      if (operatorText != "") {
        return type.withConstantValue(HaxeTypeUtils.applyUnaryOperator(type.getConstant(), operatorText)).createHolder();
      }
    }
    return typeHolder;
  }

  static ResultHolder handleIfStatement(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeIfStatement ifStatement) {
    SpecificTypeReference guardExpr = handle(ifStatement.getGuard(), context, resolver).getType();
    HaxeGuardedStatement guardedStatement = ifStatement.getGuardedStatement();
    HaxeElseStatement elseStatement = ifStatement.getElseStatement();

    PsiElement eTrue = UsefulPsiTreeUtil.getFirstChildSkipWhiteSpacesAndComments(guardedStatement);
    PsiElement eFalse = UsefulPsiTreeUtil.getFirstChildSkipWhiteSpacesAndComments(elseStatement);

    SpecificTypeReference tTrue = null;
    SpecificTypeReference tFalse = null;
    if (eTrue != null) tTrue = handle(eTrue, context, resolver).getType();
    if (eFalse != null) tFalse = handle(eFalse, context, resolver).getType();
    if (guardExpr.isConstant()) {
      if (guardExpr.getConstantAsBool()) {
        if (tFalse != null) {
          context.addUnreachable(eFalse);
        }
      } else {
        if (tTrue != null) {
          context.addUnreachable(eTrue);
        }
      }
    }

    // No 'else' clause means the if results in a Void type.
    if (null == tFalse) tFalse = SpecificHaxeClassReference.getVoid(ifStatement);

    return HaxeTypeUnifier.unify(tTrue, tFalse, ifStatement, context.getScope().unificationRules).createHolder();
  }

  static ResultHolder handleFunctionLiteral(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeFunctionLiteral function) {
    //HaxeParameterList params = function.getParameterList(); // TODO mlo: get expected type to use if signature/parameters are without types
    //if (params == null) {
    //  return SpecificHaxeClassReference.getInvalid(function).createHolder();
    //}
    LinkedList<SpecificFunctionReference.Argument> arguments = new LinkedList<>();
    ResultHolder returnType = null;
    HaxeScope scope = context.beginScope();
    scope.unificationRules = UnificationRules.PREFER_VOID;
    try {
      HaxeOpenParameterList openParamList = function.getOpenParameterList();
      HaxeParameterList parameterList = function.getParameterList();
      if (openParamList != null) {
        // Arrow function with a single, unparenthesized, parameter.
        // TODO: Infer the type from first usage in the function body. (or parameter list if used in function)
        ResultHolder argumentType = SpecificTypeReference.getUnknown(function).createHolder();
        String argumentName = openParamList.getUntypedParameter().getComponentName().getName();
        // if defined in a call expression (as an argument) we can match definitions with corresponding method  parameter
        if(function.getParent() instanceof  HaxeCallExpressionList callExpressionList
           && callExpressionList.getParent() instanceof  HaxeCallExpression callExpression
          && callExpression.getExpression() instanceof HaxeReferenceExpression referenceExpression) {

          PsiElement resolve = referenceExpression.resolve();
          if(resolve instanceof  HaxeMethod method) {
            int index = callExpressionList.getExpressionList().indexOf(function);
            HaxeCallExpressionUtil.CallExpressionValidation validation = HaxeCallExpressionUtil.checkMethodCall(callExpression, method);
            Map<Integer, Integer> indexMap = validation.getArgumentToParameterIndex();
            Integer parameterIndex = indexMap.get(index);
            ResultHolder holder = validation.getParameterIndexToType().get(parameterIndex);
            if (holder != null && !holder.isUnknown()) {
              SpecificTypeReference reference;
              if (holder.getClassType() != null){
                reference = holder.getClassType().fullyResolveTypeDefAndUnwrapNullTypeReference();
              }else {
                reference = holder.getFunctionType();
              }
              if (reference instanceof  SpecificFunctionReference functionReference) {
                  // if type found in param, override  argumentType (default is unknown)
                  if (argumentType.isUnknown() && !functionReference.getArguments().isEmpty()) {
                    SpecificFunctionReference.Argument argument = functionReference.getArguments().get(0);
                    argumentType = argument.getType();
                }
              }
            }
          }

        }
        context.setLocal(argumentName, argumentType);
        // TODO check if rest param?
        arguments.add(new SpecificFunctionReference.Argument(0, false, false, argumentType, argumentName));
      } else if (parameterList != null) {
        List<HaxeParameter> list = parameterList.getParameterList();
        for (int i = 0; i < list.size(); i++) {
          HaxeParameter parameter = list.get(i);
          //ResultHolder argumentType = HaxeTypeResolver.getTypeFromTypeTag(parameter.getTypeTag(), function);
          ResultHolder argumentType = handleWithRecursionGuard(parameter, context, resolver);
          if (argumentType == null) argumentType = SpecificTypeReference.getUnknown(parameter).createHolder();
          context.setLocal(parameter.getName(), argumentType);
          // TODO check if rest param?
          boolean optional = parameter.getOptionalMark() != null || parameter.getVarInit() != null;
          arguments.add(new SpecificFunctionReference.Argument(i, optional, false, argumentType, parameter.getName()));
        } // TODO: Add Void if list.size() == 0
      }
      context.addLambda(context.createChild(function.getLastChild()));
      HaxeTypeTag tag = (function.getTypeTag());
      if (null != tag) {
        returnType = HaxeTypeResolver.getTypeFromTypeTag(tag, function);
      } else {
        // If there was no type tag on the function, then we try to infer the value:
        // If there is a block to this method, then return the type of the block.
        //  - in order to avoid unnecessary overhead evaluating the entire block we search for
        //    return statements that belong to the method and evaluate those
        //  - if no return statement evaluate the last element  (lambda expressions)
        // If there is not a block, but there is an expression, then return the type of that expression.
        // If there is not a block, but there is a statement, then return the type of that statement.
        HaxeBlockStatement block = function.getBlockStatement();
        if (null != block) {

          List<HaxeReturnStatement> returnStatementList =
            CachedValuesManager.getCachedValue(block,  () -> HaxeTypeResolver.findReturnStatementsForMethod(block));
          List<ResultHolder> returnTypes = returnStatementList.stream().map(statement -> HaxeTypeResolver.getPsiElementType(statement, resolver)).toList();
          if (!returnTypes.isEmpty())  {
            returnType = HaxeTypeUnifier.unifyHolders(returnTypes, block, UnificationRules.PREFER_VOID);
          } else {
            // TODO cache last element
            boolean filtered = false;
            HaxePsiCompositeElement lastExpression = getLastExpressionCached(block);
            if (lastExpression != null) {
              // TODO try to eliminate any expressions that are not valid  value expressions
              if (lastExpression instanceof HaxeIfStatement ifStatement) {
                if (ifStatement.getElseStatement() == null) {
                  // ignore if statements if there's no else as it cant be a value expression
                  filtered = true;
                  returnType = SpecificFunctionReference.getVoid(block).createHolder();
                }
              }
              if (!filtered) returnType = HaxeTypeResolver.getPsiElementType(lastExpression, resolver);
            }else {
              returnType = SpecificFunctionReference.getVoid(block).createHolder();
            }
          }

        } else if (null != function.getExpression()) {
          returnType = handle(function.getExpression(), context, resolver);
        } else {
          // Only one of these can be non-null at a time.
          PsiElement possibleStatements[] = {function.getDoWhileStatement(), function.getForStatement(), function.getIfStatement(),
            function.getReturnStatement(), function.getThrowStatement(), function.getWhileStatement()};
          for (PsiElement statement : possibleStatements) {
            if (null != statement) {
              returnType = handle(statement, context, resolver);
              break;
            }
          }
        }
      }
    }
    finally {
      context.endScope();
    }
    return new SpecificFunctionReference(arguments, returnType, null, function, function).createHolder();
  }

  static HaxePsiCompositeElement getLastExpressionCached(HaxeBlockStatement block) {
   return  CachedValuesManager.getCachedValue(block, () -> {
     HaxePsiCompositeElement element = null;
     for (PsiElement child : block.getChildren()) {
       if (child instanceof  HaxePsiCompositeElement nextElement) element = nextElement;
     }
     return new CachedValueProvider.Result<>(element, block);
    });
  }
  static ResultHolder handleArrayAccessExpression(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeArrayAccessExpression arrayAccessExpression) {
    final List<HaxeExpression> list = arrayAccessExpression.getExpressionList();
    if (list.size() >= 2) {
      SpecificTypeReference left = handle(list.get(0), context, resolver).getType();
      SpecificTypeReference right = handle(list.get(1), context, resolver).getType();
      // if left is typeParameter try to use typeParameter constraints and see if it have array accessor
      if(left.isTypeParameter()) {
        ResultHolder resolve = resolver.resolve(left.createHolder());
        if(resolve != null && !resolve.isUnknown())left = resolve.getType();
      }
      if (left.isArray()) {
        Object constant = null;
        if (left.isConstant()) {
          if (left.getConstant() instanceof List array) {
            //List array = (List)left.getConstant();
            // TODO got class cast exception here due to constant being "HaxeAbstractClassDeclarationImpl
            //  possible expression causing issue: ("this[x + 1]  in  abstractType(Array<Float>)" ?)

            final HaxeRange constraint = right.getRangeConstraint();
            HaxeRange arrayBounds = new HaxeRange(0, array.size());
            if (right.isConstant()) {
              final int index = HaxeTypeUtils.getIntValue(right.getConstant());
              if (arrayBounds.contains(index)) {
                constant = array.get(index);
              }
              else {
                context.addWarning(arrayAccessExpression, "Out of bounds " + index + " not inside " + arrayBounds);
              }
            }
            else if (constraint != null) {
              if (!arrayBounds.contains(constraint)) {
                context.addWarning(arrayAccessExpression, "Out of bounds " + constraint + " not inside " + arrayBounds);
              }
            }
          }
        }
        ResultHolder arrayType = left.getArrayElementType().getType().withConstantValue(constant).createHolder();
        ResultHolder resolved = resolver.resolve(arrayType);
        return resolved.isUnknown() ? arrayType : resolved;
      }
      //if not native array, look up ArrayAccessGetter method and use result
      if(left instanceof SpecificHaxeClassReference classReference) {
        // make sure we fully resolve and unwrap any nulls and typedefs before searching for accessors
        SpecificTypeReference reference = classReference.fullyResolveTypeDefAndUnwrapNullTypeReference();
        if (reference instanceof SpecificHaxeClassReference fullyResolved){
          classReference = fullyResolved;
        }

        HaxeClass haxeClass = classReference.getHaxeClass();
        if (haxeClass != null) {
          HaxeNamedComponent getter = haxeClass.findArrayAccessGetter(resolver);
          if (getter instanceof HaxeMethodDeclaration methodDeclaration) {
            HaxeMethodModel methodModel = methodDeclaration.getModel();
            HaxeGenericResolver localResolver = classReference.getGenericResolver();
            HaxeGenericResolver methodResolver = methodModel.getGenericResolver(localResolver);
            localResolver.addAll(methodResolver);// apply constraints from methodSignature (if any)
            ResultHolder returnType = methodModel.getReturnType(localResolver);
            if (returnType.getType().isNullType()) localResolver.resolve(returnType);
            if (returnType != null) return returnType;
          }
          // hack to work around external ArrayAccess interface, interface that has no methods but tells compiler that implementing class has array access
          else if (getter instanceof HaxeExternInterfaceDeclaration interfaceDeclaration) {
            HaxeGenericSpecialization leftResolver = classReference.getGenericResolver().getSpecialization(getter);
            HaxeResolveResult resolvedInterface = HaxeResolveUtil.getHaxeClassResolveResult(interfaceDeclaration, leftResolver);
            ResultHolder type = resolvedInterface.getGenericResolver().resolve("T");
            if (type != null) return type;
          }
        }
      }
    }
    return createUnknown(arrayAccessExpression);
  }

  static ResultHolder handleIteratorExpression(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeIteratorExpression iteratorExpression) {
    final List<HaxeExpression> list = iteratorExpression.getExpressionList();
    if (list.size() >= 2) {
      final SpecificTypeReference left = handle(list.get(0), context, resolver).getType();
      final SpecificTypeReference right = handle(list.get(1), context, resolver).getType();
      Object constant = null;
      if (left.isConstant() && right.isConstant()) {
        constant = new HaxeRange(
          HaxeTypeUtils.getIntValue(left.getConstant()),
          HaxeTypeUtils.getIntValue(right.getConstant())
        );
      }
      return SpecificHaxeClassReference.getIterator(SpecificHaxeClassReference.getInt(iteratorExpression)).withConstantValue(constant)
        .createHolder();
    }
    return createUnknown(iteratorExpression);
  }

  static ResultHolder handleSuperExpression(HaxeExpressionEvaluatorContext context, HaxeGenericResolver resolver,
                                                    HaxeSuperExpression superExpression) {
    /*
    log.debug("-------------------------");
    final HaxeExpressionList list = HaxePsiUtils.getChildWithText(element, HaxeExpressionList.class);
    log.debug(element);
    log.debug(list);
    final List<HaxeExpression> parameters = (list != null) ? list.getExpressionList() : Collections.<HaxeExpression>emptyList();
    final HaxeMethodModel method = HaxeJavaUtil.cast(HaxeMethodModel.fromPsi(element), HaxeMethodModel.class);
    if (method == null) {
      context.addError(element, "Not in a method");
    }
    if (method != null) {
      final HaxeMethodModel parentMethod = method.getParentMethod();
      if (parentMethod == null) {
        context.addError(element, "Calling super without parent constructor");
      } else {
        log.debug(element);
        log.debug(parentMethod.getFunctionType());
        log.debug(parameters);
        checkParameters(element, parentMethod.getFunctionType(), parameters, context);
        //log.debug(method);
        //log.debug(parentMethod);
      }
    }
    return SpecificHaxeClassReference.getVoid(element);
    */
    final HaxeMethodModel method = HaxeJavaUtil.cast(HaxeBaseMemberModel.fromPsi(superExpression), HaxeMethodModel.class);
    final HaxeMethodModel parentMethod = (method != null) ? method.getParentMethod(resolver) : null;
    if (parentMethod != null) {
      return parentMethod.getFunctionType(resolver).createHolder();
    }
    context.addError(superExpression, "Calling super without parent constructor");
    return createUnknown(superExpression);
  }

  static ResultHolder handleValueExpression(HaxeExpressionEvaluatorContext context,
                                                    HaxeGenericResolver resolver,
                                                    HaxeValueExpression valueExpression) {
    if (valueExpression.getSwitchStatement() != null){
      return handle(valueExpression.getSwitchStatement(), context, resolver);
    }
    if (valueExpression.getIfStatement() != null){
      return handle(valueExpression.getIfStatement(), context, resolver);
    }
    if (valueExpression.getTryStatement() != null){
      return handle(valueExpression.getTryStatement(), context, resolver);
    }
    if (valueExpression.getVarInit() != null){
      return handle(valueExpression.getVarInit(), context, resolver);
    }
    if (valueExpression.getExpression() != null){
      return handle(valueExpression.getExpression(), context, resolver);
    }
    if (valueExpression.getMacroTypeReification() != null){
       return HaxeMacroTypeUtil.getComplexType(valueExpression.getMacroTypeReification()).createHolder();
    }

    if(valueExpression.getMacroExpressionReification() != null) {
      HaxeMacroExpressionReification reification = valueExpression.getMacroExpressionReification();

      HaxeMacroValueReification valueReification = reification.getMacroValueReification();
      if (valueReification != null) {
        return handle(valueReification, context, resolver);
      }
      HaxeMacroExpReification expReification = reification.getMacroExpReification();
      if(expReification != null) {
        return SpecificHaxeClassReference.getDynamic(valueExpression).createHolder();
      }

      HaxeMacroArrayReification arrayReification = reification.getMacroArrayReification();
      if (arrayReification != null) {
        return handle(arrayReification.getExpression(), context, resolver);
      }
    }
    return createUnknown(valueExpression);
  }

  static ResultHolder handlePrimitives(PsiElement element, HaxePsiToken psiToken) {
    IElementType type = psiToken.getTokenType();

    if (type == HaxeTokenTypes.LITINT || type == HaxeTokenTypes.LITHEX || type == HaxeTokenTypes.LITOCT) {
      return SpecificHaxeClassReference.primitive("Int", element, Long.decode(element.getText())).createHolder();
    } else if (type == HaxeTokenTypes.LITFLOAT) {
      Float value = Float.valueOf(element.getText());
      return SpecificHaxeClassReference.primitive("Float", element, Double.parseDouble(element.getText()))
        .withConstantValue(value)
        .createHolder();
    } else if (type == HaxeTokenTypes.KFALSE || type == HaxeTokenTypes.KTRUE) {
      Boolean value = type == HaxeTokenTypes.KTRUE;
      return SpecificHaxeClassReference.primitive("Bool", element, type == HaxeTokenTypes.KTRUE)
        .withConstantValue(value)
        .createHolder();
    } else if (type == HaxeTokenTypes.KNULL) {
      return SpecificHaxeClassReference.primitive("Dynamic", element, HaxeNull.instance).createHolder();
    } else {
      if(log.isDebugEnabled())log.debug("Unhandled token type: " + type);
      return SpecificHaxeClassReference.getDynamic(element).createHolder();
    }
  }

  static ResultHolder handleArrayLiteral(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeArrayLiteral arrayLiteral) {
    HaxeExpressionList list = arrayLiteral.getExpressionList();

    // Check if it's a comprehension.
    if (list != null) {
      final List<HaxeExpression> expressionList = list.getExpressionList();
      if (expressionList.isEmpty()) {
        final PsiElement child = list.getFirstChild();
        if ((child instanceof HaxeForStatement) || (child instanceof HaxeWhileStatement)) {
          HaxeScope scope = context.beginScope();
          scope.unificationRules = UnificationRules.IGNORE_VOID;
          ResultHolder handle = handle(child, context, resolver);
          context.endScope();
          return SpecificTypeReference.createArray(handle, arrayLiteral).createHolder();
        }
      }
    }

    ArrayList<SpecificTypeReference> references = new ArrayList<SpecificTypeReference>();
    ArrayList<Object> constants = new ArrayList<Object>();
    boolean allConstants = true;
    if (list != null) {
      for (HaxeExpression expression : list.getExpressionList()) {
        // dropping AssignHint as we are in an array so field type will include the array part.
        SpecificTypeReference type = handle(expression, context, resolver.withoutAssignHint()).getType();
        if (!type.isConstant()) {
          allConstants = false;
        } else {
          constants.add(type.getConstant());
        }
        // Convert enum Value types to Enum class  (you cant have an Array of EnumValue types)
        if (type instanceof  SpecificEnumValueReference enumValueReference) {
          type = enumValueReference.getEnumClass();
        }
        references.add(type);
      }
    }
    // an attempt at suggesting what to unify  types into (useful for when typeTag is an anonymous structure as those would never be used in normal unify)
    SpecificTypeReference suggestedType = null;
    ResultHolder  typeTagType = findInitTypeForUnify(arrayLiteral);
    if (typeTagType!= null) {
      // we expect Array<T> or collection type with type parameter (might not work properly if type is implicit cast)
      if (typeTagType.getClassType() != null) {
        @NotNull ResultHolder[] specifics = typeTagType.getClassType().getSpecifics();
        if (specifics.length == 1) {
          suggestedType = specifics[0].getType();
        }
      }
    }
    // empty expression with type tag (var x:Array<T> = []), no need to look for usage, use typetag
    if (references.isEmpty() && suggestedType != null && !suggestedType.isUnknown()) {
      return typeTagType;
    } else {
      ResultHolder elementTypeHolder = references.isEmpty()
                                       ? SpecificTypeReference.getUnknown(arrayLiteral).createHolder()
                                       : HaxeTypeUnifier.unify(references, arrayLiteral, suggestedType, UnificationRules.IGNORE_VOID).withoutConstantValue().createHolder();

      SpecificTypeReference result = SpecificHaxeClassReference.createArray(elementTypeHolder, arrayLiteral);
      if (allConstants) result = result.withConstantValue(constants);
      ResultHolder holder = result.createHolder();

      // try to resolve typeParameter when we got empty literal array with declaration without typeTag
      if (elementTypeHolder.isUnknown()) {
        // note to avoid recursive loop we only  do this check if its part of a varInit and not part of any expression,
        // it would not make sense trying to look it up in a callExpression etc because then its type should be defined in the parameter list.
        if (arrayLiteral.getParent() instanceof HaxeVarInit) {
          HaxePsiField declaringField =
            UsefulPsiTreeUtil.findParentOfTypeButStopIfTypeIs(arrayLiteral, HaxePsiField.class, HaxeCallExpression.class);
          if (declaringField != null) {
            HaxeComponentName componentName = declaringField.getComponentName();
            if(componentName != null) {
              ResultHolder searchResult = searchReferencesForTypeParameters(componentName, context, resolver, holder);
              if (!searchResult.isUnknown()) holder = searchResult;
            }
          }
        }
      }
      return holder;
    }
  }

  static ResultHolder handleMapLiteral(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeMapLiteral mapLiteral) {
    Collection<HaxeMapInitializerExpression> initializers = PsiTreeUtil.findChildrenOfType(mapLiteral, HaxeMapInitializerExpression.class);


    ArrayList<SpecificTypeReference> keyReferences = new ArrayList<>(initializers.size());
    ArrayList<SpecificTypeReference> valueReferences = new ArrayList<>(initializers.size());
    HaxeGenericResolver resolverWithoutHint = resolver.withoutAssignHint();
    for (HaxeMapInitializerExpression initializerExpression : initializers) {

      SpecificTypeReference keyType = handle(initializerExpression.getLeftHand(), context, resolverWithoutHint).getType();
      if (keyType instanceof SpecificEnumValueReference enumValueReference) {
        keyType = enumValueReference.getEnumClass();
      }
      keyReferences.add(keyType);
      SpecificTypeReference valueType = handle(initializerExpression.getRightHand(), context, resolverWithoutHint).getType();
      if (valueType instanceof SpecificEnumValueReference enumValueReference) {
        valueType = enumValueReference.getEnumClass();
      }

      valueReferences.add(valueType);
    }

    // XXX: Maybe track and add constants to the type references, like arrays do??
    //      That has implications on how they're displayed (e.g. not as key=>value,
    //      but as separate arrays).
    ResultHolder keyTypeHolder = HaxeTypeUnifier.unify(keyReferences, mapLiteral, UnificationRules.IGNORE_VOID).withoutConstantValue().createHolder();
    ResultHolder valueTypeHolder = HaxeTypeUnifier.unify(valueReferences, mapLiteral, UnificationRules.IGNORE_VOID).withoutConstantValue().createHolder();

    SpecificTypeReference result = SpecificHaxeClassReference.createMap(keyTypeHolder, valueTypeHolder, mapLiteral);
    ResultHolder holder = result.createHolder();
    return holder;
  }

  @NotNull
  static ResultHolder handleExpressionList(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeExpressionList expressionList) {
    ArrayList<ResultHolder> references = new ArrayList<ResultHolder>();
    for (HaxeExpression expression : expressionList.getExpressionList()) {
      references.add(handle(expression, context, resolver));
    }
    return HaxeTypeUnifier.unifyHolders(references, expressionList, UnificationRules.DEFAULT);
  }

  static ResultHolder handleCallExpression(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeCallExpression callExpression) {

    HaxeExpression callExpressionRef = callExpression.getExpression();
    // generateResolverFromScopeParents -  making sure we got typeParameters from arguments/parameters
    HaxeGenericResolver localResolver = HaxeGenericResolverUtil.generateResolverFromScopeParents(callExpression);
    localResolver.addAll(resolver);

    SpecificTypeReference functionType;
    if (callExpressionRef != null) {   // can be null if the entire expression is a macro  of callExpression
      // map type Parameters to methods declaring class resolver if necessary
      SpecificHaxeClassReference callieClassRef = tryGetCallieType(callExpression).getClassType();
      HaxeClass callieType = callieClassRef != null ? callieClassRef.getHaxeClass() : null;
      HaxeClass methodTypeClassType = tryGetMethodDeclaringClass(callExpression);
      if (callieType != null && methodTypeClassType != null) {
        localResolver = HaxeGenericResolverUtil.createInheritedClassResolver(methodTypeClassType, callieType, localResolver);
      }

      functionType = handle(callExpressionRef, context, localResolver).getType();
      boolean varIsMacroFunction = isCallExpressionToMacroMethod(callExpressionRef);
      boolean callIsFromMacroContext = isInMacroFunction(callExpressionRef);
      if (varIsMacroFunction && !callIsFromMacroContext) {
        ResultHolder holder = resolveMacroTypesForFunction(functionType.createHolder());
        functionType = holder.getFunctionType();
      }
    }else  if (callExpression.getMacroExpressionReification() != null) {
      functionType = SpecificTypeReference.getUnknown(callExpression.getMacroExpressionReification());
    }else {
      // should not happen
      functionType = SpecificTypeReference.getUnknown(callExpression);
    }

    // @TODO: this should be innecessary when code is working right!
    if ( functionType == null  || functionType.isUnknown()) {
      if (callExpressionRef instanceof HaxeReference) {
        PsiReference reference = callExpressionRef.getReference();
        if (reference != null) {
          PsiElement subelement = reference.resolve();
          if (subelement instanceof HaxeMethod haxeMethod) {
            functionType = haxeMethod.getModel().getFunctionType(resolver);
          }
        }
      }
    }

    if (functionType == null || functionType.isUnknown()) {
      if(log.isDebugEnabled()) log.debug("Couldn't resolve " + callExpressionRef);
    }

    List<HaxeExpression> parameterExpressions = null;
    if (callExpression.getExpressionList() != null) {
      parameterExpressions = callExpression.getExpressionList().getExpressionList();
    } else {
      parameterExpressions = Collections.emptyList();
    }

    if (functionType instanceof  SpecificHaxeClassReference classReference && classReference.isTypeDef() ) {
      functionType = classReference.fullyResolveTypeDefReference();
    }
    if (functionType instanceof SpecificEnumValueReference enumValueConstructor) {
      // TODO, this probably should not be handled here, but its detected as a call expression


      SpecificHaxeClassReference enumClass = enumValueConstructor.enumClass;
      HaxeGenericResolver enumResolver = enumClass.getGenericResolver();
      SpecificFunctionReference constructor = enumValueConstructor.getConstructor();

      List<ResultHolder> list = parameterExpressions.stream()
        .map(expression -> HaxeExpressionEvaluator.evaluate(expression, new HaxeExpressionEvaluatorContext(expression), enumResolver).result)
        .toList();


      ResultHolder holder = enumClass.createHolder();
      SpecificHaxeClassReference type = holder.getClassType();
      @NotNull ResultHolder[] specifics = type.getSpecifics();
      // convert any parameter that matches argument of type TypeParameter into specifics for enum type
      HaxeGenericParam param = enumClass.getHaxeClass().getGenericParam();
      List<HaxeGenericParamModel> params = enumClass.getHaxeClassModel().getGenericParams();

      Map<String, List<ResultHolder>> genericsMap = new HashMap<>();
      params.forEach(g -> genericsMap.put(g.getName(), new ArrayList<>()));

      for (HaxeGenericParamModel model : params) {
        String genericName = model.getName();

        int parameterIndex = 0;
        List<SpecificFunctionReference.Argument> arguments = constructor.getArguments();
        for (int argumentIndex = 0; argumentIndex < arguments.size(); argumentIndex++) {
          SpecificFunctionReference.Argument argument = arguments.get(argumentIndex);
          if (parameterIndex < list.size()) {
            ResultHolder parameter = list.get(parameterIndex++);
            if (argument.getType().canAssign(parameter)) {
              if (argument.getType().getType() instanceof SpecificHaxeClassReference classReference ){
                if (classReference.isTypeParameter() && genericName.equals(classReference.getClassName())) {
                  genericsMap.get(genericName).add(parameter);
                } else {
                  if (argument.getType().isClassType()) {
                    SpecificHaxeClassReference classType = parameter.getClassType();
                    HaxeGenericResolver parameterResolver = classType != null ? classType.getGenericResolver() : new HaxeGenericResolver();
                    ResultHolder test = parameterResolver.resolve(genericName);
                    if (test != null && !test.isUnknown()) {
                      genericsMap.get(genericName).add(parameter);
                    }
                  }
                }
              }
            }
          }
        }
      }
      // unify all usage of generics
      for (int i = 0; i < params.size(); i++) {
        HaxeGenericParamModel g = params.get(i);
        String name = g.getName();
        List<ResultHolder> holders = genericsMap.get(name);
        ResultHolder unified = HaxeTypeUnifier.unifyHolders(holders, callExpression, UnificationRules.DEFAULT);
        enumResolver.add(name, unified, ResolveSource.CLASS_TYPE_PARAMETER);
        specifics[i] = unified;
      }
      return holder;

    }
    if (functionType instanceof SpecificFunctionReference ftype) {

      ResultHolder returnType = ftype.getReturnType();
      boolean nullWrapped = returnType.isNullWrappedType();

      HaxeGenericResolver functionResolver = new HaxeGenericResolver();
      functionResolver.addAll(resolver.withoutArgumentType());

      // if reference to "real" method, try to use any argument to type parameter mapping
      if (ftype.method != null && returnType.containsTypeParameters()) {
        HaxeCallExpressionUtil.CallExpressionValidation validation = HaxeCallExpressionUtil.checkMethodCall(callExpression, ftype.method.getMethod());
        functionResolver.addAll(validation.getResolver());
      }

      ResultHolder resolved = functionResolver.resolveReturnType(returnType.tryUnwrapNullType());
      if (resolved != null && !resolved.isUnknown()) {
        if(nullWrapped) resolved = resolved.wrapInNullType();
        returnType = resolved;
      }
      if(returnType.isUnknown() || returnType.isDynamic() || returnType.isVoid()) {
        return returnType.duplicate();
      }

      if(returnType.isFunctionType()){
        return returnType.getFunctionType().createHolder();
      }

      if(returnType.isClassType() || returnType.isEnumValueType()) {
        return returnType.withOrigin(ftype.context);
      }

    }

    if (functionType.isDynamic()) {
      for (HaxeExpression expression : parameterExpressions) {
        handle(expression, context, resolver);
      }

      return functionType.withoutConstantValue().createHolder();
    }

    // @TODO: resolve the function type return type
    return createUnknown(callExpression);
  }

  private static HaxeClass tryGetMethodDeclaringClass(HaxeCallExpression expression) {
    if (expression.getExpression() instanceof HaxeReference reference) {
      final PsiElement resolved = reference.resolve();
      if (resolved instanceof HaxeMethod method) {
        HaxeMethodModel model = method.getModel();
        if(model != null) {
          HaxeClassModel classModel = model.getDeclaringClass();
          if(classModel != null) return classModel.haxeClass;
        }
      }
    }
    return null;
  }

  private static boolean isInMacroFunction(HaxeExpression ref) {
    HaxeMethodDeclaration type = PsiTreeUtil.getParentOfType(ref, HaxeMethodDeclaration.class);
    if (type != null && type.getModel() != null) {
      return type.getModel().isMacro();
    }
    return false;
  }

  private static boolean isCallExpressionToMacroMethod(HaxeExpression callExpressionRef) {
    if (callExpressionRef instanceof HaxeReference) {
      PsiReference reference = callExpressionRef.getReference();
      if (reference != null) {
        PsiElement subelement = reference.resolve();
        if (subelement instanceof HaxeMethod haxeMethod) {
          return haxeMethod.getModel().isMacro();
        }
      }
    }
    return false;
  }


  @Nullable
  static ResultHolder handleVarInit(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeVarInit varInit) {
    final HaxeExpression expression = varInit.getExpression();
    if (expression == null) {
      return SpecificTypeReference.getInvalid(varInit).createHolder();
    }
    return handleWithRecursionGuard(expression, context, resolver);

  }

  @Nullable
  static ResultHolder handleLocalVarDeclaration(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeLocalVarDeclaration varDeclaration) {
    final HaxeComponentName name = varDeclaration.getComponentName();
    final HaxeVarInit init = varDeclaration.getVarInit();
    final HaxeTypeTag typeTag = varDeclaration.getTypeTag();

    ResultHolder result = null;
    HaxeGenericResolver localResolver = new HaxeGenericResolver();
    localResolver.addAll(resolver);

    if(init != null) {
      // find any type parameters used in init expression as the return type might be of that type
      HaxeGenericResolver initResolver = HaxeGenericResolverUtil.generateResolverFromScopeParents(init.getExpression());
      localResolver.addAll(initResolver.withoutUnknowns());
    }

    if (typeTag != null) {
      result = HaxeTypeResolver.getTypeFromTypeTag(typeTag, varDeclaration);
      ResultHolder resolve = resolver.resolve(result);
      if (!resolve.isUnknown()) result = resolve;
    }

    if (result == null && init != null) {
      result = _handle(init, context, localResolver);
    }

    // search for usage to determine type
    HaxeComponentName element = varDeclaration.getComponentName();
    final ResultHolder hint = result;

    if (result == null || isDynamicBecauseOfNullValueInit(result)) {
      result = tryToFindTypeFromUsage(element, result, hint, context, resolver, null);
    }

    if (isUnknownLiteralArray(result) && result.containsUnknownTypeParameters()) {
      result = searchReferencesForTypeParameters(name, context, resolver, result);
    }

    result = tryGetEnumValuesDeclaringClass(result);
    context.setLocal(name.getText(), result);
    return result;
  }


  private static @Nullable ResultHolder tryGetEnumValuesDeclaringClass(ResultHolder result) {
    if (result != null && result.isEnumValueType()) {
      //TODO check if typeParams need to be copied over
      result = result.getEnumValueType().getEnumClass().createHolder();
    }
    return result;
  }

  private static boolean isUnknownLiteralArray(ResultHolder result) {
    if (result == null) return false;
    SpecificHaxeClassReference classType = result.getClassType();
    if (classType != null && result.getClassType().isArray()) {
      @NotNull ResultHolder[] specifics = classType.getSpecifics();
      if (specifics.length == 1) {
        ResultHolder specific = specifics[0];
        if (specific.isUnknown() || isUnknownLiteralArray(specific)) return true;
      }
      if (classType.context instanceof HaxeArrayLiteral arrayLiteral) {
        return arrayLiteral.getExpressionList() == null;
      }
    }
    return false;
  }

  public static boolean isDynamicBecauseOfNullValueInit(ResultHolder result) {
    if (result == null) return false;
    return  result.getType().isDynamic() && result.getType().getConstant() instanceof HaxeNull;
  }

  static ResultHolder handleAssignExpression(HaxeExpressionEvaluatorContext context, HaxeGenericResolver resolver, PsiElement element) {
    final PsiElement left = element.getFirstChild();
    final PsiElement right = element.getLastChild();
    if (left != null && right != null) {
      final ResultHolder leftResult = handle(left, context, resolver);
      final ResultHolder rightResult = handle(right, context, resolver);

      if (leftResult.isUnknown()) {
        leftResult.setType(rightResult.getType());
        context.setLocalWhereDefined(left.getText(), leftResult);
      }
      leftResult.removeConstant();

      final SpecificTypeReference leftValue = leftResult.getType();
      final SpecificTypeReference rightValue = rightResult.getType();

      //leftValue.mutateConstantValue(null);

      // skipping `canAssign` check if we dont have a holder to add annotations to
      // this is probably just waste of time when resolving in files we dont have open.
      // TODO try to  see if we need this or can move it so its not executed unnessesary
      if (context.holder != null) {
        if (!leftResult.canAssign(rightResult)) {

          List<HaxeExpressionConversionFixer> fixers = HaxeExpressionConversionFixer.createStdTypeFixers(right, rightValue, leftValue);
          AnnotationBuilder builder = HaxeStandardAnnotation
            .typeMismatch(context.holder, right, rightValue.toStringWithoutConstant(), leftValue.toStringWithoutConstant())
            .withFix(new HaxeCastFixer(right, rightValue, leftValue));

          fixers.forEach(builder::withFix);
          builder.create();
        }
      }

      if (leftResult.isImmutable()) {
        context.addError(element, HaxeBundle.message("haxe.semantic.trying.to.change.an.immutable.value"));
      }

      return rightResult;
    }
    return createUnknown(element);
  }

  static ResultHolder handleLocalVarDeclarationList(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeLocalVarDeclarationList varDeclarationList) {
    // Var declaration list is a statement that returns a Void type, not the type of the local vars it creates.
    // We still evaluate its sub-parts so that we can set the known value types of variables in the scope.
    for (HaxeLocalVarDeclaration part : varDeclarationList.getLocalVarDeclarationList()) {
      handle(part, context, resolver);
    }
    return SpecificHaxeClassReference.getVoid(varDeclarationList).createHolder();
  }

  static ResultHolder handleWhileStatement(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeWhileStatement whileStatement) {
    HaxeDoWhileBody whileBody = whileStatement.getBody();
    if (whileBody != null) {
      @NotNull PsiElement[] children = whileBody.getChildren();
      PsiElement lastChild = children[children.length - 1];
      return handle(lastChild, context, resolver);
    }
    return createUnknown(whileStatement);
  }

  static ResultHolder handleCastExpression(HaxeCastExpression castExpression) {
    HaxeTypeOrAnonymous anonymous = castExpression.getTypeOrAnonymous();
    if (anonymous != null) {
      return HaxeTypeResolver.getTypeFromTypeOrAnonymous(anonymous);
    } else {
      return createUnknown(castExpression);
    }
  }

  @NotNull
  static ResultHolder handleRestParameter(HaxeRestParameter restParameter) {
    HaxeTypeTag tag = restParameter.getTypeTag();
    ResultHolder type = HaxeTypeResolver.getTypeFromTypeTag(tag, restParameter);
    return new ResultHolder(SpecificTypeReference.getStdClass(ARRAY, restParameter, new ResultHolder[]{type}));
  }

  static ResultHolder handleIdentifier(HaxeExpressionEvaluatorContext context, HaxeIdentifier identifier) {
    //makes sure its a variable and not a reification expression
    if (isMacroVariable(identifier)) {
      return SpecificTypeReference.getDynamic(identifier).createHolder();
    }
    // If it has already been seen, then use whatever type is already known.
    ResultHolder holder = context.get(identifier.getText());
    if (holder == null) {
      // context.addError(element, "Unknown variable", new HaxeCreateLocalVariableFixer(element.getText(), element));

      return SpecificTypeReference.getUnknown(identifier).createHolder();
    }

    return holder;
  }

  static ResultHolder handleThisExpression(HaxeGenericResolver resolver, HaxeThisExpression thisExpression) {
    //PsiReference reference = element.getReference();
    //HaxeClassResolveResult result = HaxeResolveUtil.getHaxeClassResolveResult(element);
    HaxeClass ancestor = UsefulPsiTreeUtil.getAncestor(thisExpression, HaxeClass.class);
    if (ancestor == null) return SpecificTypeReference.getDynamic(thisExpression).createHolder();
    HaxeClassModel model = ancestor.getModel();
    if (model.isAbstractType()) {
      SpecificHaxeClassReference reference = model.getUnderlyingClassReference(resolver);
      if (null != reference) {
        return reference.createHolder();
      }
    }
    ResultHolder[] specifics =  HaxeTypeResolver.resolveDeclarationParametersToTypes(model.haxeClass, resolver);
    return SpecificHaxeClassReference.withGenerics(new HaxeClassReference(model, thisExpression), specifics).createHolder();
  }

  @NotNull
  static ResultHolder handleSwitchCaseBlock(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeSwitchCaseBlock caseBlock) {
    List<HaxeReturnStatement> list = caseBlock.getReturnStatementList();
    for (HaxeReturnStatement  statement : list) {
      ResultHolder returnType = handle(statement, context, resolver);
      context.addReturnType(returnType, statement);
    }
    List<HaxeExpression> expressions = caseBlock.getExpressionList();
    if (!expressions.isEmpty()) {
      HaxeExpression lastExpression = expressions.get(expressions.size() - 1);
      return handle(lastExpression, context, resolver);
    }
    return new ResultHolder(SpecificHaxeClassReference.getVoid(caseBlock));
  }

  @NotNull
  static ResultHolder handleSwitchStatement(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeSwitchStatement switchStatement) {
    // TODO: Evaluating result of switch statement should properly implemented
    List<SpecificTypeReference> typeList = new LinkedList<>();
    SpecificTypeReference bestGuess = null;

    if(switchStatement.getSwitchBlock() != null) {
      List<HaxeSwitchCase> caseList = switchStatement.getSwitchBlock().getSwitchCaseList();

      for (HaxeSwitchCase switchCase : caseList) {
        HaxeSwitchCaseBlock block = switchCase.getSwitchCaseBlock();
        if (block != null) {
          ResultHolder handle = handle(block, context, resolver);
          if (!handle.isUnknown())typeList.add(handle.getType());
        }
      }

      for (SpecificTypeReference typeReference : typeList) {
        if (typeReference.isVoid()) continue;
        if (bestGuess == null) {
          bestGuess = typeReference;
          continue;
        }
        bestGuess = HaxeTypeUnifier.unify(bestGuess, typeReference, switchStatement);
      }
    }

    if (bestGuess != null) {
      return new ResultHolder(bestGuess);
    }else {
      return new ResultHolder(SpecificHaxeClassReference.getUnknown(switchStatement));
    }
  }

  static ResultHolder handleCodeBlock(HaxeExpressionEvaluatorContext context, HaxeGenericResolver resolver, PsiElement element) {
    context.beginScope();
    context.getScope().deepSearchForReturnValues = true;

    ResultHolder type = createUnknown(element);
    boolean deadCode = false;
    for (PsiElement childElement : element.getChildren()) {
      // not sure why comments are available here but to avoid overhead we filter them out
      if (ONLY_COMMENTS.contains( childElement.getNode().getElementType())) continue;
      type = handle(childElement, context, resolver);
      if (deadCode) {
        //context.addWarning(childElement, "Unreachable statement");
        context.addUnreachable(childElement);
      }
      if (childElement instanceof HaxeReturnStatement) {
        deadCode = true;
      }
    }
    context.endScope();
    return type;
  }

  static ResultHolder handleIterable(
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver,
    HaxeIterable iterable) {
    ResultHolder iteratorParent = handle(iterable.getExpression(), context, resolver);
    SpecificTypeReference type = iteratorParent.getType();
    if (!type.isNumeric()) {
      if (iteratorParent.isClassType()) {
        SpecificHaxeClassReference haxeClassReference = iteratorParent.getClassType();
        HaxeGenericResolver localResolver =  new HaxeGenericResolver();
        localResolver.addAll(resolver);
        localResolver.addAll(haxeClassReference.getGenericResolver());// replace parent/old resolver values with newer from class reference
        if (haxeClassReference != null && haxeClassReference.getHaxeClassModel() != null) {
          HaxeForStatement parentForLoop = PsiTreeUtil.getParentOfType(iterable, HaxeForStatement.class);

          if(haxeClassReference.isTypeDefOfClass()) {
            SpecificTypeReference typeReference = haxeClassReference.fullyResolveTypeDefReference();
            if (typeReference instanceof  SpecificHaxeClassReference classReference) {
              HaxeGenericResolver typeDefResolved = classReference.getGenericResolver();
              localResolver.addAll(typeDefResolved);
            }
          }

          if (parentForLoop.getKeyValueIterator() == null) {
            HaxeBaseMemberModel iterator = haxeClassReference.getHaxeClassModel().getMember("iterator", resolver);
            if (iterator instanceof HaxeMethodModel methodModel) {
              return methodModel.getReturnType(localResolver);
            }
          }else {
            HaxeBaseMemberModel iterator = haxeClassReference.getHaxeClassModel().getMember("keyValueIterator", resolver);
            if (iterator instanceof HaxeMethodModel methodModel) {
              return methodModel.getReturnType(localResolver);
            }
          }
        }
      }
    }

    return handle(iterable.getExpression(), context, resolver);
  }

  @NotNull
  static ResultHolder handleTryStatement(HaxeExpressionEvaluatorContext context,
                                                 HaxeGenericResolver resolver,
                                                 HaxeTryStatement tryStatement) {
    //  try-catch can be used as a value expression all blocks must be evaluated and unified
    //  we should also iterate trough so we can pick up any return statements
    @NotNull PsiElement[] children = tryStatement.getChildren();
    List<ResultHolder> blockResults = new ArrayList<>();
    for (PsiElement child : children) {
      blockResults.add(handle(child, context, resolver));
    }
    UnificationRules rules = context.getScope().unificationRules;
    return HaxeTypeUnifier.unifyHolders(blockResults, tryStatement, rules);
  }
  @NotNull
  static ResultHolder handleCatchStatement(HaxeExpressionEvaluatorContext context,
                                           HaxeGenericResolver resolver,
                                           HaxeCatchStatement catchStatement) {
    //  try-catch can be used as a value expression all blocks must be evaluated and unified
    //  we should also iterate trough so we can pick up any return statements
    @NotNull PsiElement[] children = catchStatement.getChildren();
    HaxeParameter parameter = catchStatement.getParameter();
    List<ResultHolder> blockResults = new ArrayList<>();
    for (PsiElement child : children) {
      if (child == parameter) continue;
      blockResults.add(handle(child, context, resolver));
    }
    UnificationRules rules = context.getScope().unificationRules;
    return HaxeTypeUnifier.unifyHolders(blockResults, catchStatement, rules);
  }



  static ResultHolder handleReturnStatement(HaxeExpressionEvaluatorContext context,
                                                    HaxeGenericResolver resolver,
                                                    HaxeReturnStatement returnStatement) {

    ResultHolder result = SpecificHaxeClassReference.getVoid(returnStatement).createHolder();
    if (isUntypedReturn(returnStatement)) return result;
    List<PsiElement> children = withoutMetadata(returnStatement.getChildren());
    if (!children.isEmpty()) {
      PsiElement child = children.get(0);
      result = handle(child, context, resolver);
    }
    context.addReturnType(result, returnStatement);
    return result;
  }

  private static List<PsiElement> withoutMetadata(PsiElement[] children) {
    return Stream.of(children).filter(child-> {
      if (child instanceof LazyParseablePsiElement element) {
        if (element.getElementType() instanceof HaxeEmbeddedElementType) {
          return false;
        }
      }
      return true;
    }).toList();
  }

  static ResultHolder handleImportAlias(PsiElement element, HaxeImportAlias alias) {
    HaxeResolveResult result = alias.resolveHaxeClass();
    HaxeClass haxeClass = result.getHaxeClass();
    if (haxeClass == null) {
      return new ResultHolder(SpecificHaxeClassReference.getUnknown(element));
    }else {
      @NotNull ResultHolder[] specifics = result.getGenericResolver().getSpecificsFor(haxeClass);
      return SpecificHaxeClassReference.withGenerics(new HaxeClassReference(haxeClass.getModel(), element), specifics).createHolder();
    }
  }

  static SpecificTypeReference resolveAnyTypeDefs(SpecificTypeReference reference) {
    if (reference instanceof SpecificHaxeClassReference classReference && classReference.isTypeDef()) {
      if(classReference.isTypeDefOfFunction()) {
        return classReference.resolveTypeDefFunction();
      }else {
        SpecificHaxeClassReference resolvedClass = classReference.resolveTypeDefClass();
        return resolveAnyTypeDefs(resolvedClass);
      }
    }
    return reference;
  }

  static void checkParameters(
    final PsiElement callelement,
    final HaxeMethodModel method,
    final List<HaxeExpression> arguments,
    final HaxeExpressionEvaluatorContext context,
    final HaxeGenericResolver resolver
  ) {
    checkParameters(callelement, method.getFunctionType(resolver), arguments, context, resolver);
  }

  static void checkParameters(
    PsiElement callelement,
    SpecificFunctionReference ftype,
    List<HaxeExpression> parameterExpressions,
    HaxeExpressionEvaluatorContext context,
    HaxeGenericResolver resolver
  ) {
    if (!context.isReportingErrors()) return;

    List<SpecificFunctionReference.Argument> parameterTypes = ftype.getArguments();

    int parameterTypesSize = parameterTypes.size();
    int parameterExpressionsSize = parameterExpressions.size();
    int len = Math.min(parameterTypesSize, parameterExpressionsSize);

    for (int n = 0; n < len; n++) {
      ResultHolder type = HaxeTypeResolver.resolveParameterizedType(parameterTypes.get(n).getType(), resolver);
      HaxeExpression expression = parameterExpressions.get(n);
      ResultHolder value = handle(expression, context, resolver);

      if (context.holder != null) {
        if (!type.canAssign(value)) {
          context.addError(
            expression,
            "Can't assign " + value + " to " + type,
            new HaxeCastFixer(expression, value.getType(), type.getType())
          );
        }
      }
    }

    //log.debug(ftype.getDebugString());
    // More parameters than expected
    if (parameterExpressionsSize > parameterTypesSize) {
      for (int n = parameterTypesSize; n < parameterExpressionsSize; n++) {
        context.addError(parameterExpressions.get(n), "Unexpected argument");
      }
    }
    // Less parameters than expected
    else if (parameterExpressionsSize < ftype.getNonOptionalArgumentsCount()) {
      context.addError(callelement, "Less arguments than expected");
    }
  }

  static private String getOperator(PsiElement field, TokenSet set) {
    ASTNode operatorNode = field.getNode().findChildByType(set);
    if (operatorNode == null) return "";
    return operatorNode.getText();
  }

  static int getDistance(PsiReference reference, int offset) {
    return reference.getAbsoluteRange().getStartOffset() - offset;
  }

  static boolean isMacroVariable(HaxeIdentifier identifier) {
    if (identifier instanceof  HaxeMacroIdentifier macroIdentifier) return macroIdentifier.getMacroId() != null;
    return false;
  }

  @Nullable
  static ResultHolder findInitTypeForUnify(@NotNull PsiElement field) {
    HaxeVarInit varInit = PsiTreeUtil.getParentOfType(field, HaxeVarInit.class);
    if (varInit != null) {
      HaxeFieldDeclaration type = PsiTreeUtil.getParentOfType(varInit, HaxeFieldDeclaration.class);
      if (type!= null) {
        HaxeTypeTag tag = type.getTypeTag();
        if (tag != null) {
          ResultHolder typeTag = HaxeTypeResolver.getTypeFromTypeTag(tag, field);
          if (!typeTag.isUnknown()) {
            return typeTag;
          }
        }
      }
    }
    return null;
  }

  //private static boolean containsTypeParameters(ResultHolder holder) {
  //  if (holder.isUnknown()) return  false;
  //  if (holder.isTypeParameter()) return true;
  //  SpecificTypeReference type = holder.getType();
  //  if (type instanceof  SpecificHaxeClassReference classReference) {
  //    for (ResultHolder specific : classReference.getSpecifics()) {
  //      if (containsTypeParameters(specific)) return  true;
  //    }
  //  }
  //  if (type instanceof SpecificFunctionReference  function) {
  //    if(!function.getTypeParameters().isEmpty()) return true;
  //  }
  //  return false;
  //}

  static boolean isUntypedReturn(HaxeReturnStatement statement) {
    PsiElement child = statement.getFirstChild();
    while(child != null) {
      if (child instanceof HaxePsiToken psiToken) {
        if (psiToken.getTokenType() == KUNTYPED) return true;
      }
      child = child.getNextSibling();
    }
    return false;
  }




}
