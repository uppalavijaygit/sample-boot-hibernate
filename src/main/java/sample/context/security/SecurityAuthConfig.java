package sample.context.security;

import java.io.IOException;
import java.util.*;

import javax.servlet.*;
import javax.servlet.http.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.*;
import org.springframework.security.config.annotation.web.configuration.*;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.*;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.*;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.GenericFilterBean;

import lombok.*;
import sample.ValidationException.ErrorKeys;
import sample.context.actor.ActorSession;
import sample.context.security.SecurityActorFinder.*;
import sample.context.security.SecurityConfig.SecurityProperties;

/**
 * Spring Security(認証/認可)全般の設定を行います。
 * <p>認証はベーシック認証ではなく、HttpSessionを用いた従来型のアプローチで定義しています。
 * <p>設定はパターンを決め打ちしている関係上、既存の定義ファイルをラップしています。
 * securityプリフィックスではなくextension.securityプリフィックスのものを利用してください。
 * <p>low: 本サンプルでは無効化していますが、CSRF対応はプロジェクト毎に適切な利用を検討してください。
 */
@Setter
@Getter
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true, proxyTargetClass = true)
@ConditionalOnProperty(prefix = "extension.security.auth", name = "enabled", matchIfMissing = true)
@Order(org.springframework.boot.autoconfigure.security.SecurityProperties.ACCESS_OVERRIDE_ORDER)
public class SecurityAuthConfig extends WebSecurityConfigurerAdapter {

	/** Spring Boot のサーバ情報 */
	@Autowired
	private ServerProperties serverProps;
	/** 拡張セキュリティ情報 */
	@Autowired
	private SecurityProperties props;
	/** 認証/認可利用者サービス */
	@Autowired
	private SecurityActorFinder actorFinder;
	/** カスタム認証プロバイダ */
	@Autowired
	private SecurityProvider securityProvider;
	/** カスタム認証マネージャ */
	@Autowired
	@Lazy
	private AuthenticationManager authenticationManager;
	/** カスタムエントリポイント(例外対応) */
	@Autowired
	private SecurityEntryPoint entryPoint;
	/** ログイン/ログアウト時の拡張ハンドラ */
	@Autowired
	private LoginHandler loginHandler;
	/** ThreadLocalスコープの利用者セッション */
	@Autowired
	private ActorSession actorSession;
	@Autowired(required = false)
	private SecurityFilters filters;

	@Override
	public void configure(AuthenticationManagerBuilder auth) throws Exception {
		auth.eraseCredentials(true).authenticationProvider(securityProvider);
	}
	
	@Bean
	@ConditionalOnBean(SecurityAuthConfig.class)
    @Override
    public AuthenticationManager authenticationManagerBean() throws Exception {
        return super.authenticationManagerBean();
    }
	
	@Override
    public void configure(WebSecurity web) throws Exception {
		web.ignoring().antMatchers(serverProps.getPathsArray(props.auth().ignorePath));
    }
	
	@Override
	protected void configure(HttpSecurity http) throws Exception {
		// Target URL
		http
			.authorizeRequests()
			.antMatchers(props.auth().excludesPath).permitAll();
		http
			.csrf().disable()
			.authorizeRequests()
			.antMatchers(props.auth().path).hasRole("USER");
		http
			.csrf().disable()
			.authorizeRequests()
			.antMatchers(props.auth().pathAdmin).hasRole("ADMIN");
		// common
		http
			.exceptionHandling().authenticationEntryPoint(entryPoint);
		http
			.sessionManagement()
			.maximumSessions(props.auth().maximumSessions)
			.and()
			.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED);
		http
			.addFilterAfter(new ActorSessionFilter(actorSession), UsernamePasswordAuthenticationFilter.class);
		if (filters != null) {
			for (Filter filter : filters.filters()) {
				http.addFilterAfter(filter, ActorSessionFilter.class);
			}
		}

		// login/logout
		http
			.formLogin().loginPage(props.auth().loginPath)
			.usernameParameter(props.auth().loginKey).passwordParameter(props.auth().passwordKey)
			.successHandler(loginHandler).failureHandler(loginHandler)
			.permitAll()
			.and()
			.logout().logoutUrl(props.auth().logoutPath)
			.logoutSuccessHandler(loginHandler)
			.permitAll();
	}

	/** Spring Securityに対する拡張設定情報。(ScurityConfig#SecurityPropertiesによって管理されています) */
	@Data
	public static class SecurityAuthProperties {
		/** リクエスト時のログインIDを取得するキー */
		private String loginKey = "loginId";
		/** リクエスト時のパスワードを取得するキー */
		private String passwordKey = "password";
		/** 認証対象パス */
		private String[] path = new String[] {"/api/**"};
		/** 認証対象パス(管理者向け) */
		private String[] pathAdmin = new String[] {"/api/admin/**"};
		/** 認証除外パス(認証対象からの除外) */
		private String[] excludesPath = new String[] {"/api/system/job/**"};
		/** 認証無視パス(フィルタ未適用の認証未考慮、静的リソース等) */
		private String[] ignorePath = new String[] {"/css/**", "/js/**", "/img/**", "/**/favicon.ico"};
		/** ログインAPIパス */
		private String loginPath = "/api/login";
		/** ログアウトAPIパス */
		private String logoutPath = "/api/logout";
		/** 一人が同時利用可能な最大セッション数 */
		private int maximumSessions = 2;
		/**
		 * 社員向けモードの時はtrue。
		 * <p>ログインパスは同じですが、ログイン処理の取り扱いが切り替わります。
		 * <ul>
		 * <li>true: SecurityUserService
		 * <li>false: SecurityAdminService
		 * </ul> 
		 */
		private boolean admin = false;
	}

	/** Spring Securityに対するFilter拡張設定。Filterを追加したい時は本I/Fを継承してBean登録してください。 */
	public static interface SecurityFilters {
		List<Filter> filters();
	}
	
	/**
	 * Spring Securityのカスタム認証プロバイダ。
	 * <p>主にパスワード照合を行っています。
	 */
	@Component
	@ConditionalOnBean(SecurityAuthConfig.class)
	public static class SecurityProvider implements AuthenticationProvider {
		@Autowired
		private SecurityActorFinder actorFinder;
		@Autowired
		@Lazy
		private PasswordEncoder encoder;
		@Override
		public Authentication authenticate(Authentication authentication) throws AuthenticationException {
	        if (authentication.getPrincipal() == null ||
	        		authentication.getCredentials() == null) {
	            throw new BadCredentialsException("ログイン認証に失敗しました");
	        }
	        SecurityActorService service = actorFinder.detailsService();
	        UserDetails details =
	        		service.loadUserByUsername(authentication.getPrincipal().toString());
	        String presentedPassword = authentication.getCredentials().toString();
	        if (!encoder.matches(presentedPassword, details.getPassword())) {
	        	throw new BadCredentialsException("ログイン認証に失敗しました");
	        }
	        UsernamePasswordAuthenticationToken ret =
	        	new UsernamePasswordAuthenticationToken(
	        		authentication.getName(), "", details.getAuthorities());
			ret.setDetails(details);
			return ret;
		}
		@Override
		public boolean supports(Class<?> authentication) {
			return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
		}
	}

	/**
	 * Spring Securityのカスタムエントリポイント。
	 * <p>API化を念頭に例外発生時の実装差込をしています。
	 */
	@Component
	@ConditionalOnBean(SecurityAuthConfig.class)
	public static class SecurityEntryPoint implements AuthenticationEntryPoint {
		@Autowired
		private MessageSource msg;
		@Override
		public void commence(HttpServletRequest request, HttpServletResponse response,
				AuthenticationException authException) throws IOException, ServletException {
			String message = msg.getMessage(ErrorKeys.Authentication, new Object[0], Locale.getDefault());
			if (authException instanceof InsufficientAuthenticationException) {
				message = msg.getMessage(ErrorKeys.AccessDenied, new Object[0], Locale.getDefault());
			}
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED, message);
		}
	}
	
	/**
	 * SpringSecurityの認証情報(Authentication)とActorSessionを紐付けるServletFilter。
	 * <p>dummyLoginが有効な時は常にSecurityContextHolderへAuthenticationを紐付けます。 
	 */
	@AllArgsConstructor
	public static class ActorSessionFilter extends GenericFilterBean {
		private ActorSession actorSession;
		@Override
		public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
				throws IOException, ServletException {
			Optional<Authentication> authOpt = SecurityActorFinder.authentication();
			if (authOpt.isPresent() && authOpt.get().getDetails() instanceof ActorDetails) {
				ActorDetails details = (ActorDetails)authOpt.get().getDetails();
				actorSession.bind(details.actor());
				try {
					chain.doFilter(request, response);
				} finally {
					actorSession.unbind();
				}
			} else {
				actorSession.unbind();
				chain.doFilter(request, response);	
			}
		}
	}

	/**
	 * Spring Securityにおけるログイン/ログアウト時の振る舞いを拡張するHandler。
	 */
	@Component
	@ConditionalOnBean(SecurityAuthConfig.class)
	@Getter
	@Setter
	public static class LoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler, LogoutSuccessHandler {
		@Autowired
		private SecurityProperties props;

		/** ログイン成功処理 */
		@Override
		public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
				Authentication authentication) throws IOException, ServletException {
			Optional.ofNullable((ActorDetails)authentication.getDetails()).ifPresent(
				(detail) -> detail.bindRequestInfo(request));
			if (response.isCommitted()) return;
			writeReponseEmpty(response, HttpServletResponse.SC_OK);
		}

		/** ログイン失敗処理 */
		@Override
		public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
				AuthenticationException exception) throws IOException, ServletException {
			if (response.isCommitted()) return;
			writeReponseEmpty(response, HttpServletResponse.SC_BAD_REQUEST);
		}

		/** ログアウト成功処理 */
		@Override
		public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
				throws IOException, ServletException {
			if (response.isCommitted()) return;
			writeReponseEmpty(response, HttpServletResponse.SC_OK);
		}
		
		private void writeReponseEmpty(HttpServletResponse response, int status) throws IOException {
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setStatus(status);
			response.getWriter().write("{}");			
		}		
	}
	
}
