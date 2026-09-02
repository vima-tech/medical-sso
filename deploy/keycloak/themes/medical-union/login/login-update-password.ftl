<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('password','password-confirm'); section>
    <#if section = "header">
        ${msg("updatePasswordTitle")}
    <#elseif section = "form">
        <form id="update-password-form" action="${url.loginAction}" method="post" novalidate>

            <div class="field">
                <label for="password-new">${msg("passwordNew")}</label>
                <div class="password-box">
                    <input id="password-new" name="password-new" type="password" autofocus autocomplete="new-password"
                           aria-invalid="${messagesPerField.existsError('password','password-confirm')?c}"/>
                    <button type="button" class="peek" data-toggle="password-new"
                            aria-label="${msg("showPassword")}">显示</button>
                </div>
                <#if messagesPerField.existsError('password')>
                    <p class="field-error" aria-live="polite">${kcSanitize(messagesPerField.get('password'))?no_esc}</p>
                </#if>
            </div>

            <div class="field">
                <label for="password-confirm">${msg("passwordConfirm")}</label>
                <div class="password-box">
                    <input id="password-confirm" name="password-confirm" type="password" autocomplete="new-password"
                           aria-invalid="${messagesPerField.existsError('password-confirm')?c}"/>
                    <button type="button" class="peek" data-toggle="password-confirm"
                            aria-label="${msg("showPassword")}">显示</button>
                </div>
                <#if messagesPerField.existsError('password-confirm')>
                    <p class="field-error" aria-live="polite">${kcSanitize(messagesPerField.get('password-confirm'))?no_esc}</p>
                </#if>
            </div>

            <label class="remember">
                <input type="checkbox" id="logout-sessions" name="logout-sessions" value="on" checked>
                <span>${msg("logoutOtherSessions")}</span>
            </label>

            <#if isAppInitiatedAction??>
                <button class="submit" name="login" type="submit">${msg("doSubmit")}</button>
                <button class="secondary-button" type="submit" name="cancel-aia" value="true">${msg("doCancel")}</button>
            <#else>
                <button class="submit" name="login" type="submit">${msg("doSubmit")}</button>
            </#if>
        </form>

        <p class="help">${msg("updatePasswordHelp")}</p>
    </#if>
</@layout.registrationLayout>
