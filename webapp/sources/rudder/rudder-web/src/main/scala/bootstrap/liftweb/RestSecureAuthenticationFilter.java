package bootstrap.liftweb;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Filter to check that X-Requested-With header is present and has the value
 * XMLHttpRequest, to mitigate CSRF attacks.
 */
public class RestSecureAuthenticationFilter implements Filter {

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		HttpServletRequest httpRequest = (HttpServletRequest) request;
		HttpServletResponse httpResponse = (HttpServletResponse) response;

		String requestedWithHeader = httpRequest.getHeader("X-Requested-With");
		if (requestedWithHeader == null || !requestedWithHeader.equals("XMLHttpRequest")) {
			httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			httpResponse.getWriter().write("CSRF mitigation header missing or invalid.");
			return;
		}

		chain.doFilter(request, response);
	}

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
	}

	@Override
	public void destroy() {
	}
}
