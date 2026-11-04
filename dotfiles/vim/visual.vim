" colorize for syntax
syntax on

" Show full-width (zenkaku) spaces
function! ZenkakuSpace()
    highlight ZenkakuSpace cterm=reverse ctermfg=DarkGray gui=reverse guifg=DarkGray
endfunction

if has('syntax')
    augroup ZenkakuSpace
    autocmd!
    "Comment out the next line if ZenkakuSpace is set in the color scheme
    autocmd ColorScheme       * call ZenkakuSpace()
    autocmd VimEnter,WinEnter * match ZenkakuSpace /　/
    augroup END
    call ZenkakuSpace()
endif

" Apply the my_monokai scheme installed in .vim/colors
" Error 'Cannot find color scheme' will be caused if you set colorscheme
" before dein setting and 'syntax on'
colorscheme my_monokai
set t_Co=256
