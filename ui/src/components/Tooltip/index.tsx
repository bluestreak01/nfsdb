import React, { ReactNode, Ref } from "react"
import styled, { css } from "styled-components"

import { Text } from "components"
import { color } from "utils"

type Props = Readonly<{
  arrow?: {
    setArrowElement: Ref<HTMLDivElement>
    styles?: Record<string, string>
  }
  children: ReactNode
}>

const baseStyles = css`
  position: absolute;
  width: 7px;
  height: 7px;
`

export const TooltipArrow = styled.div`
  ${baseStyles};

  &::before {
    ${baseStyles};
    top: 0;
    left: 0;
    content: "";
    transform: rotate(45deg);
    background: ${color("draculaBackgroundDarker")};
    border: 1px solid ${color("gray1")};
    border-radius: 1px;
  }
`

export const Wrapper = styled.div`
  position: relative;
  max-width: 260px;
  padding: 1rem;
  background: ${color("draculaBackgroundDarker")};
  border: 1px solid ${color("gray1")};
  border-radius: 1px;

  &[data-popper-placement^="right"] ${TooltipArrow} {
    left: -4px;

    &::before {
      border-right: none;
      border-top: none;
    }
  }

  &[data-popper-placement^="left"] ${TooltipArrow} {
    right: -4px;

    &::before {
      border-left: none;
      border-bottom: none;
    }
  }

  &[data-popper-placement^="top"] ${TooltipArrow} {
    bottom: -4px;

    &::before {
      border-left: none;
      border-top: none;
    }
  }

  &[data-popper-placement^="bottom"] ${TooltipArrow} {
    top: -4px;

    &::before {
      border-right: none;
      border-bottom: none;
    }
  }
`

export const Tooltip = ({ arrow, children, ...rest }: Props) => (
  <Wrapper {...rest}>
    <Text color="draculaForeground">{children}</Text>
    {arrow && <TooltipArrow ref={arrow.setArrowElement} style={arrow.styles} />}
  </Wrapper>
)
