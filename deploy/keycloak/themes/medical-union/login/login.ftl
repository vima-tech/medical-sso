<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username','password'); section>
    <#if section = "header">
        ${msg("loginPageTitle")}
    <#elseif section = "form">
        <form id="login-form" action="${url.loginAction}" method="post" novalidate>

            <#if !usernameHidden??>
                <div class="field">
                    <label for="username">${msg("usernameOrEmail")}</label>
                    <input id="username" name="username" type="text" autofocus autocomplete="username"
                           value="${(login.username!'')}" dir="ltr"
                           placeholder="${msg("usernamePlaceholder")}"
                           aria-invalid="${messagesPerField.existsError('username','password')?c}"/>
                </div>
            </#if>

            <div class="field">
                <label for="password">${msg("password")}</label>
                <div class="password-box">
                    <input id="password" name="password" type="password" autocomplete="current-password"
                           placeholder="${msg("passwordPlaceholder")}"
                           aria-invalid="${messagesPerField.existsError('username','password')?c}"/>
                    <button type="button" class="peek" data-toggle="password"
                            aria-label="${msg("showPassword")}">显示</button>
                </div>
            </div>

            <#if messagesPerField.existsError('username','password')>
                <p class="field-error" aria-live="polite">
                    ${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}
                </p>
            </#if>

            <#if realm.rememberMe && !usernameHidden??>
                <label class="remember">
                    <input name="rememberMe" type="checkbox" <#if login.rememberMe??>checked</#if>>
                    <span>${msg("rememberMe")}</span>
                </label>
            </#if>

            <input type="hidden" id="id-hidden-input" name="credentialId"
                   <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
            <button class="submit" name="login" id="kc-login" type="submit">${msg("doLogIn")}</button>

            <#if realm.resetPasswordAllowed>
                <a class="secondary-link" href="${url.loginResetCredentialsUrl}">${msg("doForgotPassword")}</a>
            </#if>
        </form>

        <p class="help">${msg("loginHelp")}</p>
    </#if>
</@layout.registrationLayout>
