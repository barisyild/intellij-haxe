/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2015 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2018-2019 Eric Bishton
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.plugins.haxe.model.type;

import com.intellij.plugins.haxe.lang.psi.HaxeClass;
import com.intellij.plugins.haxe.lang.psi.HaxeGenericSpecialization;
import com.intellij.plugins.haxe.model.HaxeGenericParamModel;
import com.intellij.plugins.haxe.model.type.resolver.ResolverEntry;
import com.intellij.plugins.haxe.model.type.resolver.ResolveSource;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static com.intellij.plugins.haxe.model.type.resolver.ResolveSource.*;

public class HaxeGenericResolver {
  // This must remain ordered, thus the LinkedHashMap.
  //final private LinkedHashMap<String, ResolverEntry> resolvers;
  final private LinkedList<ResolverEntry> resolvers;
  final private LinkedList<ResolverEntry> constaints;

  public HaxeGenericResolver() {
    this.resolvers = new LinkedList<>();
    this.constaints = new LinkedList<>();
  }

  public HaxeGenericResolver withoutUnknowns() {
    HaxeGenericResolver resolver = new HaxeGenericResolver();

    for (ResolverEntry resolverEntry : resolvers) {
      if (!resolverEntry.type().isUnknown()) {
        resolver.resolvers.add(resolverEntry);
      }
    }
    for (ResolverEntry entry : constaints) {
      if (!entry.type().isUnknown()) {
        resolver.constaints.add(entry);
      }
    }
    return resolver;
  }
  public HaxeGenericResolver withoutAssignHint() {
    HaxeGenericResolver resolver = new HaxeGenericResolver();

    for (ResolverEntry entry : resolvers) {
      if (entry.resolveSource() != ResolveSource.ASSIGN_TYPE) {
        resolver.resolvers.add(entry);
      }
    }
    resolver.constaints.addAll(constaints);
    return resolver;
  }
  public HaxeGenericResolver withoutConstraints() {
    HaxeGenericResolver resolver = new HaxeGenericResolver();
    resolver.resolvers.addAll(this.resolvers);

    return resolver;
  }

  /*
     when resolving types inside a method with generic parameters we want to show the generic types and not unknown
      this method creates a new resolver and replaces its unknowns with  GenericParams from generic params models
   */
  public HaxeGenericResolver withTypeParametersAsType(@NotNull List<HaxeGenericParamModel> params) {
    HaxeGenericResolver resolver = new HaxeGenericResolver();
    resolver.resolvers.addAll(resolvers);

    for (HaxeGenericParamModel param : params) {
      String name = param.getName();
      Optional<ResolverEntry> match = resolver.resolvers.stream()
        .filter(entry -> entry.name().equals(name))
        .filter(entry -> entry.type().isUnknown())
        .findAny();

      if(match.isPresent()) {
        HaxeClassReference classReference = new HaxeClassReference(name, param.getPsi(), true);
        ResultHolder holder = new ResultHolder(SpecificHaxeClassReference.withoutGenerics(classReference));
        resolver.resolvers.add(new ResolverEntry(name, holder, ResolveSource.TODO));
      }
    }
    resolver.constaints.addAll(constaints);
    return resolver;
  }
  @Deprecated
  public ResultHolder add(@NotNull String name, @NotNull ResultHolder specificType) {
    return add (name, specificType, ResolveSource.TODO);
  }
  public ResultHolder add(@NotNull String name, @NotNull ResultHolder specificType, ResolveSource resolveSource) {
    specificType = replaceAnyEnumValueWithEnumClass(specificType);
    resolvers.removeIf(entry -> entry.name().equals(name) && entry.resolveSource() == resolveSource);
    resolvers.add(new ResolverEntry(name, specificType, resolveSource));

    addForTypeParameterConstraints(name, specificType);

    return specificType;
  }

  private static ResultHolder replaceAnyEnumValueWithEnumClass(@NotNull ResultHolder specificType) {
    // EnumValues cant be typeParameters, replacing with declaring EnumClass
    if (specificType.isEnumValueType()){
      specificType = specificType.getEnumValueType().getEnumClass().createHolder();
    }
    return specificType;
  }

  private void addForTypeParameterConstraints(@NotNull String name, @NotNull ResultHolder specificType) {
    ResultHolder updatedSpecificType = replaceAnyEnumValueWithEnumClass(specificType);
    if(updatedSpecificType.isTypeParameter()) return;
    Optional<ResolverEntry> constraint = findConstraint(name);
    if (constraint.isPresent()) {
      ResolverEntry entry = constraint.get();
      if(entry.type().isTypeParameter()) {
        String typeName = ((SpecificHaxeClassReference)entry.type().getType()).getClassName();
        boolean match = resolvers.stream().anyMatch(e -> e.name().equals(typeName)
                                                     && e.type().equals(updatedSpecificType)
                                                     && e.resolveSource().equals(entry.resolveSource())
        );
        // recursion guard
        if(!match) {
          add(typeName, updatedSpecificType, entry.resolveSource());
        }
      }
    }
  }

  @NotNull
  private Optional<ResolverEntry> findConstraint(@NotNull String name) {
    return constaints.stream().filter(entry -> entry.name().equals(name)).findFirst();
  }

  public ResultHolder addConstraint(@NotNull String name, @NotNull ResultHolder specificType, ResolveSource resolveSource) {
    specificType = replaceAnyEnumValueWithEnumClass(specificType);
    constaints.removeIf(entry -> entry.name().equals(name) && entry.resolveSource() == resolveSource);
    constaints.add(new ResolverEntry(name, specificType, resolveSource));
    return specificType;
  }

  @NotNull
  public HaxeGenericResolver addAll(@Nullable HaxeGenericResolver parentResolver) {
    if (null != parentResolver && parentResolver != this) {
      // not using "collection.addAll" because there is extra logic in add() that we need to execute
      for (ResolverEntry resolver : parentResolver.resolvers) {
        this.add(resolver.name(), resolver.type(), resolver.resolveSource());
      }
      for (ResolverEntry entry : parentResolver.constaints) {
        this.addConstraint(entry.name(), entry.type(), entry.resolveSource());
      }

    }
    return this;
  }
  @NotNull
  public HaxeGenericResolver addAll(@Nullable HaxeGenericResolver parentResolver, ResolveSource type) {
    if (null != parentResolver && parentResolver != this) {
      // not using "collection.addAll" because there is extra logic in add() that we need to execute
      for (ResolverEntry resolver : parentResolver.resolvers) {
        if (resolver.resolveSource() == type) this.add(resolver.name(), resolver.type(), resolver.resolveSource());
      }
      for (ResolverEntry entry : parentResolver.constaints) {
        if (entry.resolveSource() == type)  this.addConstraint(entry.name(), entry.type(), entry.resolveSource());
      }

    }
    return this;
  }

  private void removeExisting(@NotNull LinkedList<ResolverEntry> newValues, LinkedList<ResolverEntry> oldValues ) {
    LinkedList<ResolverEntry> removeList = new LinkedList<>();
    for (ResolverEntry newVal : newValues) {
      oldValues.stream()
        .filter(entry -> entry.name().equals(newVal.name()))
        .filter(entry -> entry.resolveSource().equals(newVal.resolveSource()))
        .findFirst()
        .ifPresent(removeList::add);
    }
    oldValues.removeAll(removeList);
  }

  @Nullable
  public ResultHolder resolve(String name) {
    ResultHolder holder = resolvers.stream()
      .filter(entry -> entry.name().equals(name)).min(this::ResolverPrioritySort)
      .map(ResolverEntry::type)
      .orElse(null);
    // fallback to constraints ?
    if (holder == null) {
      holder = constaints.stream()
        .filter(entry -> entry.name().equals(name)).min(this::ResolverPrioritySort)
        .map(ResolverEntry::type)
        .orElse(null);
    }
    // continue resolving until no longer typeParameter or no match
    if (holder!= null && holder.isTypeParameter()){
      ResultHolder resolve = this.without(name).resolve(holder);
      if (resolve != null && !resolve.isUnknown()) holder = resolve;
    }
    return holder;
  }
  @Nullable
  public ResultHolder resolveArgument(String name) {
    return resolvers.stream()
      .filter(entry -> entry.resolveSource() == ARGUMENT_TYPE)
      .filter(entry -> entry.name().equals(name))
      .map(ResolverEntry::type)
      .findFirst()
      .orElse(null);
  }
  @Nullable
  public ResultHolder resolveConstraint(String name) {
    return constaints.stream()
        .filter(entry -> entry.name().equals(name))
        .map(ResolverEntry::type)
        .findFirst()
        .orElse(null);
  }

  /**
   * resolve method that helps avoid the use of getText() on elements, using textMatches() which should be faster
   * <a href="https://plugins.jetbrains.com/docs/intellij/psi-performance.html">PSI Performance</a>
   */
  @Nullable
  public ResultHolder resolve(PsiElement element) {
   return  resolve(element, false);
  }
  @Nullable
  public ResultHolder resolve(PsiElement element, boolean useAssignHint) {
   ResultHolder holder = resolvers.stream()
      .filter(entry -> element.textMatches(entry.name())).min(this::ResolverPrioritySort)
      .map(ResolverEntry::type)
      .orElse(null);

    // if not specified by normal usage  try constraints or assignment
    if (holder == null) {
      holder = constaints.stream()
        .filter(entry -> element.textMatches(entry.name())).min(this::ResolverPrioritySort)
        .map(ResolverEntry::type)
        .orElse(null);

      //if none of the method parameters specifies the type parameter
      // and only the return type uses the type parameter
      // then the assign value is what defines the type-parameter
      if (useAssignHint && holder != null) {
        holder = useAssignHintIfPossible(holder);
      }
    }
    return holder;
  }

  private int ResolverPrioritySort(ResolverEntry entry, ResolverEntry entry1) {
    int priorityA = entry.resolveSource().priority;
    int priorityB = entry1.resolveSource().priority;
    return Integer.compare(priorityA, priorityB);
  }

  @Nullable
  public ResultHolder resolveReturnType(SpecificHaxeClassReference reference) {
    if (null == reference ) return null;
    if (reference.isTypeParameter()) {
      String className = reference.getHaxeClassReference().name;
      List<ResolverEntry> resolveValues = resolvers.stream().filter(entry -> entry.name().equals(className)).toList();
      List<ResolverEntry> constraints = constaints.stream().filter(entry -> entry.name().equals(className)).toList();
      if (resolveValues.isEmpty())  {
        Optional<ResolverEntry> assign = findAssignToType();

        // if we know expected value and dont have any resolves
        if (assign.isPresent()) {
          return assign.get().type();
        }
        // if we got constraints but no resolve value, use constraint
        if (!constraints.isEmpty()) {
          return constraints.get(0).type();
        }
      }else {
        // list should be sorted so first element is correct
        return resolveValues.get(0).type();
      }
    }
    // todo recursion guard
    if (!reference.isTypeParameter() && needResolve(reference)) {
      return HaxeTypeResolver.resolveParameterizedType(reference.createHolder(), this, true);
    }
    return reference.createHolder();
  }

  private boolean needResolve(SpecificHaxeClassReference reference) {
    return Arrays.stream(reference.getSpecifics()).anyMatch(ResultHolder::isTypeParameter);
  }

  @NotNull
  private Optional<ResolverEntry> findAssignToType() {
    return resolvers.stream()
      .filter(entry -> entry.resolveSource() == ResolveSource.ASSIGN_TYPE)
      .findFirst();
  }
  public ResultHolder getAssignHint() {
    return resolvers.stream()
      .filter(entry -> entry.resolveSource() == ResolveSource.ASSIGN_TYPE)
      .findFirst()
      .map(ResolverEntry::type)
      .orElse(null);
  }

  @Nullable
  public ResultHolder resolveReturnType(ResultHolder resultHolder) {
    if (null == resultHolder ) return null;
    if (resultHolder.getType().isTypeParameter()) {
      String className = resultHolder.getClassType().getClassName();
      List<ResolverEntry> list = resolvers.stream().filter(entry -> entry.name().equals(className)).sorted(this::ResolverPrioritySort).toList();
      if (list.isEmpty())  {
        Optional<ResolverEntry> assign = findAssignToType();
        if (assign.isPresent()) {
          return assign.get().type();
        }
      }else {
        // list should be sorted so first element is correct
        return list.get(0).type();
      }
    }
    if (!resultHolder.getType().isTypeParameter()) {
      Optional<ResolverEntry> assign = findAssignToType();
      if (assign.isPresent()) { // if we got expected return type we want to pass along expected typeParameter values when resolving
        ResultHolder assignHint = assign.get().type();
        SpecificHaxeClassReference expectedType = assignHint.getClassType();
        if (expectedType != null) {
          //TODO clean up this hack

          // if hint is null type and resolve target is not wrap resolve target
          if (expectedType.isNullType() && !resultHolder.getType().isNullType()) {
            SpecificTypeReference type = resultHolder.getType();
            resultHolder = SpecificTypeReference.getNull(type.getElementContext(), resultHolder).createHolder();

          }
          //else if target is nullType and assignHint is not wrap target
          if (!expectedType.isNullType() && resultHolder.getType().isNullType()) {
            expectedType = SpecificTypeReference.getNull(expectedType.getElementContext(), assignHint);
          }
          
            ResultHolder holder = HaxeTypeResolver.resolveParameterizedType(resultHolder, this, true);
            replaceSpecifics(holder, expectedType);
            return holder;

        }
      }
      return HaxeTypeResolver.resolveParameterizedType(resultHolder, this, true);
    }
    return null;

  }

  // TODO should probably fix this method so it sets specifics instead of direct manipulation
  private static void replaceSpecifics(ResultHolder holder, SpecificHaxeClassReference expectedType) {
    SpecificHaxeClassReference resolved = holder.getClassType();
    if (resolved != null) {
      if (resolved.isTypeDefOfClass()) {
        resolved = resolved.resolveTypeDefClass();
      }
      if (expectedType.isTypeDefOfClass()) {
        expectedType = expectedType.resolveTypeDefClass();
      }
      if (expectedType != null && resolved != null) {
      @NotNull ResultHolder[] resolvedSpecifics = resolved.getSpecifics();
        @NotNull ResultHolder[] expectedSpecifics = expectedType.getSpecifics();
        if (resolvedSpecifics.length == expectedSpecifics.length) {
          for (int i = 0; i < resolvedSpecifics.length; i++) {
            ResultHolder expected = expectedSpecifics[i];
            if (!expected.isUnknown() && !expected.isTypeParameter()) {
              if (resolvedSpecifics[i].canAssign(expected)) {
                resolvedSpecifics[i] = expected;
              }
            }
          }
        }
      }
    }
  }

  @Nullable
  public ResultHolder resolve(ResultHolder resultHolder) {
    if (null == resultHolder ) return null;
    if (resultHolder.isFunctionType()) {
      SpecificFunctionReference resolve = resolve(resultHolder.getFunctionType());
      return resolve != null ? resolve.createHolder(): null;
    }
    return HaxeTypeResolver.resolveParameterizedType(resultHolder, this);
  }
  @Nullable
  public SpecificFunctionReference resolve(SpecificFunctionReference fnRef) {
    return resolve(fnRef, false);
  }
  public SpecificFunctionReference resolve(SpecificFunctionReference fnRef, boolean useAssignHint) {
    if (null == fnRef ) return null;
    if (resolvers.isEmpty() && constaints.isEmpty()) return fnRef;

      List<SpecificFunctionReference.Argument> arguments = fnRef.getArguments();
      ResultHolder returnType = fnRef.getReturnType();

      if (useAssignHint) {
        Optional<ResolverEntry> assignHint = findAssignToType();
        if (assignHint.isPresent()) {
          ResolverEntry hint = assignHint.get();
          ResultHolder hintType = hint.type();
          if (hintType.isFunctionType()) {
            SpecificFunctionReference hintFunction = hintType.getFunctionType();

            List<SpecificFunctionReference.Argument> hintArguments = hintFunction.getArguments();
            List<SpecificFunctionReference.Argument> newArgumentList = new ArrayList<>();
            int argumentCount = Math.min(arguments.size(), hintArguments.size());
            for (int i = 0; i < argumentCount; i++) {
              SpecificFunctionReference.Argument argument = arguments.get(i);
              if (argument.getType().isTypeParameter()) {
                newArgumentList.add(argument.withType(hintArguments.get(i).getType()));
              }
              else {
                newArgumentList.add(argument);
              }
            }
            if (returnType.isTypeParameter()) {
              returnType = hintFunction.getReturnType();
            }
            if (fnRef.functionType != null) {
              return new SpecificFunctionReference(newArgumentList, returnType, fnRef.functionType, fnRef.context);
            }else {
              return new SpecificFunctionReference(newArgumentList, returnType, fnRef.method, fnRef.context);
            }
          }
        }
      }


      List<SpecificFunctionReference.Argument> newArgList = arguments.stream()
        .map(argument -> argument.withType(Optional.ofNullable(resolve(argument.getType())).orElse(argument.getType())))
        .toList();

      ResultHolder newReturnType = resolve(returnType);
      ResultHolder returnValue = Optional.ofNullable(newReturnType).orElse(returnType);

    if (fnRef.functionType != null) {
      return new SpecificFunctionReference(newArgList, returnValue, fnRef.functionType, fnRef.context);
    }else {
      return new SpecificFunctionReference(newArgList, returnValue, fnRef.method, fnRef.context);
    }


  }

  /**
   * @return The names of all generics in this resolver in order of their adding.
   */
  @NotNull
  public String[] names() {
    return resolvers.stream().map(ResolverEntry::name).toArray(String[]::new);
  }
  @NotNull
  public ResolverEntry[] entries() {
    return resolvers.toArray(ResolverEntry[]::new);
  }

  /**
   * @return All specific generic types in this resolver in the order of their adding.
   */
  @NotNull
  public ResultHolder[] getSpecifics() {
    if (resolvers.isEmpty()) return ResultHolder.EMPTY;
    ResultHolder results[] = new ResultHolder[resolvers.size()];
    int i = 0;
    for (ResolverEntry resolverEntry : resolvers) {
      results[i++] = resolverEntry.type();
    }
    return results;
  }

  @NotNull
  public ResultHolder[] getSpecificsFor(@Nullable HaxeClassReference clazz) {
    return getSpecificsFor(clazz != null ? clazz.getHaxeClass() : null);
  }

  @NotNull
  public ResultHolder[] getSpecificsFor(@Nullable HaxeClass hc) {
    if (null == hc) return ResultHolder.EMPTY;

    return HaxeTypeResolver.resolveDeclarationParametersToTypes(hc, this);
  }

  /**
   * @return whether or not this resolver has any entries.
   */
  public boolean isEmpty() {
    return resolvers.isEmpty() && constaints.isEmpty();
  }

  @NotNull
  public HaxeGenericSpecialization getSpecialization(@Nullable PsiElement element) {
    return HaxeGenericSpecialization.fromGenericResolver(element, this);
  }


  public HaxeGenericResolver without(ResultHolder holder) {
    String name = findNameFor(holder);
    if (name != null) {
      return without(name);
    }
    else {
      return this;
    }
  }
  public HaxeGenericResolver without(String name) {
    HaxeGenericResolver resolver = new HaxeGenericResolver();
    for (ResolverEntry resolverEntry : resolvers) {
      if (!resolverEntry.name().equals(name)) {
        resolver.resolvers.add(resolverEntry);
      }
    }
    for (ResolverEntry entry : constaints) {
      if (!entry.name().equals(name)) {
        resolver.constaints.add(entry);
      }
    }
    return resolver;
  }
  public HaxeGenericResolver copy() {
    HaxeGenericResolver resolver = new HaxeGenericResolver();
    resolver.resolvers.addAll(resolvers);
    resolver.constaints.addAll(constaints);
    return resolver;
  }

  private ResultHolder useAssignHintIfPossible(ResultHolder type) {
    Optional<ResolverEntry> assign = findAssignToType();
    if(assign.isPresent()) {
      ResultHolder assignHint = assign.get().type();
      if (type.canAssign(assignHint)) return assignHint;
    }
    return type;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof HaxeGenericResolver otherResolver) {
      if (otherResolver.resolvers.size() != resolvers.size()) return false;
      if (otherResolver.constaints.size() != constaints.size()) return false;

      for (int i = 0; i < otherResolver.resolvers.size(); i++) {
        ResolverEntry otherEntry = otherResolver.resolvers.get(i);
        ResolverEntry thisEntry = resolvers.get(i);
        if (!otherEntry.equals(thisEntry)) return false;
      }

      for (int i = 0; i < otherResolver.constaints.size(); i++) {
        ResolverEntry otherEntry = otherResolver.constaints.get(i);
        ResolverEntry thisEntry = constaints.get(i);
        if (!otherEntry.equals(thisEntry)) return false;
      }

      return true;
    }
    return false;
  }

  @Override
  public int hashCode() {
    return resolvers.hashCode() * constaints.hashCode();
  }

  public String findNameFor(ResultHolder specific) {
    for (ResolverEntry entry : resolvers) {
      if (specific.isTypeParameter() && entry.name().equals(specific.getClassType().getClassName())){
        return entry.name();
      }
      else if (entry.type().equals(specific)) {
        return entry.name();
      }
    }
    return null;
  }


  public SpecificFunctionReference substituteTypeParamsWithAssignHintTypes(SpecificFunctionReference type) {
    Optional<ResolverEntry> assignHint = findAssignToType();
    if (assignHint.isEmpty()) return type;

    ResultHolder assignType = assignHint.get().type();
    SpecificFunctionReference functionTypeHint = assignType.getFunctionType();
    if (functionTypeHint == null) return type;

    List<SpecificFunctionReference.Argument> originalArguments = type.getArguments();
    List<SpecificFunctionReference.Argument> hintArguments = functionTypeHint.getArguments();

    LinkedList<SpecificFunctionReference.Argument> args = new LinkedList<>();

    int hintArgumentCount = hintArguments.size();
    int orignalArgumentCount = originalArguments.size();
    for (int i = 0; i < orignalArgumentCount; i++) {
      SpecificFunctionReference.Argument argument = originalArguments.get(i);
      if (argument.isTypeParameter() && i < hintArgumentCount) {
        SpecificFunctionReference.Argument hint = hintArguments.get(i);
        args.add(new SpecificFunctionReference.Argument(i, argument.isOptional(), argument.isRest(), hint.getType(), argument.getName()));
      }else {
        args.add(argument);
      }
    }
    ResultHolder returnType = type.getReturnType();
    if (returnType.isTypeParameter()) {
      returnType = functionTypeHint.getReturnType();
    }
    if (type.method != null) {
      return new SpecificFunctionReference(args, returnType,  type.method, type.context );
    }else {
      return new SpecificFunctionReference(args, returnType,  type.functionType, type.context);
    }


  }

  public String toCacheString() {
    if (isEmpty()) return "EMPTY";

    StringBuilder builder = new StringBuilder(128);

    builder.append("resolvers:[");
    for (ResolverEntry resolver : resolvers) {
      builder.append(resolver.name()).append(":").append(resolver.type().toPresentationString()).append(":")
        .append(resolver.resolveSource());
    }

    builder.append("], constraints: [");
    for (ResolverEntry entry : constaints) {
      builder.append(entry.name()).append(":").append(entry.type().toPresentationString()).append(":").append(entry.resolveSource());
    }
    builder.append("]");

    return builder.toString();

  }

  public void removeAll(String[] names) {
    for (String name : names) {
      resolvers.removeIf(entry -> entry.name().equals(name));
      constaints.removeIf(entry -> entry.name().equals(name));
    }
  }

  public HaxeGenericResolver removeClassScopeIfMethodIsPresent() {
    List<String> methodTypeParameters = new ArrayList<>();
    methodTypeParameters.addAll(resolvers.stream().filter(entry -> entry.resolveSource() == METHOD_TYPE_PARAMETER).map(ResolverEntry::name).toList());
    methodTypeParameters.addAll(constaints.stream().filter(entry -> entry.resolveSource() == METHOD_TYPE_PARAMETER).map(ResolverEntry::name).toList());
    HaxeGenericResolver copy = copy();
    copy.resolvers.removeIf(entry ->methodTypeParameters.contains(entry.name())  &&  entry.resolveSource() == CLASS_TYPE_PARAMETER);
    copy.constaints.removeIf(entry ->methodTypeParameters.contains(entry.name())  &&  entry.resolveSource() == CLASS_TYPE_PARAMETER);
    return copy;
  }

  public HaxeGenericResolver withoutMethodTypeParameters() {
    return without(METHOD_TYPE_PARAMETER);
  }
  public HaxeGenericResolver withoutArgumentType() {
    return without(ARGUMENT_TYPE);
  }

  public HaxeGenericResolver withoutClassTypeParameters() {
    return without(CLASS_TYPE_PARAMETER);
  }
  public HaxeGenericResolver without(ResolveSource source) {
    HaxeGenericResolver copy = copy();
    copy.resolvers.removeIf(entry -> entry.resolveSource() == source);
    copy.constaints.removeIf(entry -> entry.resolveSource() == source);
    return copy;
  }
}
