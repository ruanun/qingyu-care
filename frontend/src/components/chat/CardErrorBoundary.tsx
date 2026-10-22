import { Component, type ReactNode } from 'react';

interface Props {
  fallback: ReactNode;
  children: ReactNode;
}

interface State {
  hasError: boolean;
}

/** 卡片渲染错误边界：模型吐畸形结构致渲染抛错时，降级为纯文本兜底，不崩整页（fail-soft）。 */
export class CardErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false };

  static getDerivedStateFromError(): State {
    return { hasError: true };
  }

  componentDidCatch() {
    /* 静默降级，避免噪声 */
  }

  render() {
    return this.state.hasError ? this.props.fallback : this.props.children;
  }
}
