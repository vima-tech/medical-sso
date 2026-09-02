<#--
  医共体统一身份认证登录页外壳。

  这里完全接管 HTML，不继承 Keycloak 自带的 PatternFly 结构和样式。
  宏名与 section 约定与官方 base 主题保持一致，这样没有单独覆盖的页面
  （错误页、会话过期页等）仍能沿用同一套外观。
-->
<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false>
<!DOCTYPE html>
<html lang="${lang!'zh-CN'}">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="robots" content="noindex, nofollow">
    <title>${msg("loginAccountTitle")}</title>
    <#if properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}" rel="stylesheet"/>
        </#list>
    </#if>
</head>
<body class="page ${bodyClass}">

<main class="shell">
    <section class="brand">
        <div class="brand-mark" aria-hidden="true">+</div>
        <h1 class="brand-name">${(realm.displayName!msg("loginAccountTitle"))}</h1>
        <p class="brand-sub">${msg("brandSubtitle")}</p>
    </section>

    <section class="card">
        <h2 class="card-title"><#nested "header"></h2>

        <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
            <div class="alert alert-${message.type}" role="alert">${kcSanitize(message.summary)?no_esc}</div>
        </#if>

        <#if auth?? && auth.showUsername() && !auth.showResetCredentials()>
            <div class="current-user">
                <span>${auth.attemptedUsername}</span>
                <a href="${url.loginRestartFlowUrl}">${msg("restartLoginTooltip")}</a>
            </div>
        </#if>

        <#nested "form">

        <#if displayInfo>
            <div class="card-info"><#nested "info"></div>
        </#if>
    </section>

    <footer class="foot">
        <span>${msg("footerNotice")}</span>
    </footer>
</main>

<script>
    // 密码显示切换。不引入 Keycloak 自带脚本，这里自己实现。
    document.addEventListener('click', function (event) {
        var button = event.target.closest('[data-toggle]');
        if (!button) {
            return;
        }
        var input = document.getElementById(button.getAttribute('data-toggle'));
        if (!input) {
            return;
        }
        var shown = input.type === 'text';
        input.type = shown ? 'password' : 'text';
        button.textContent = shown ? '显示' : '隐藏';
    });
</script>

</body>
</html>
</#macro>
